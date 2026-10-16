import { type Cursor, invertSequence, transformCursor, transformSequence } from './engine'
import type { Operation, OperationSequence } from './operation'
import { type DocumentContext, type OtState, Synchronized } from './states'

interface UndoUnit {
  forward: Operation[]
  before: string
  kind: 'insert' | 'delete'
}

export interface RemoteParticipant {
  readonly userId: string
  readonly displayName: string
  readonly colour: string
}

export interface ClientDocumentOptions {
  send: (ops: OperationSequence, baseRevision: number, clientOpId: string) => void
  onChange: () => void
  newClientOpId: () => string
  sendCursor?: (anchor: number, head: number, baseRevision: number) => void
  content?: string
  revision?: number
}

/**
 * Drives one document through the three OT states. The UI calls {@link localEdit} on a keystroke;
 * the transport calls {@link onSync} / {@link onServerOp} on incoming frames. An op echoed with a
 * client-op id that matches the one in flight is this client's acknowledgement; anything else is a
 * remote edit.
 */
export class ClientDocument implements DocumentContext {
  content: string
  revision: number
  /** Remote carets, held in this client's displayed coordinate frame. */
  readonly remoteCursors = new Map<string, Cursor>()
  readonly participants = new Map<string, RemoteParticipant>()
  private state: OtState = new Synchronized()
  /** True from a reconnect until the pending op is transmitted over the live socket, so it is re-sent once. */
  private needsRetransmit = false
  /** Undo/redo entries are inverse sequences kept in the live frame; the open unit coalesces a typing run. */
  private undoStack: OperationSequence[] = []
  private redoStack: OperationSequence[] = []
  private openUnit: UndoUnit | null = null

  constructor(private readonly options: ClientDocumentOptions) {
    this.content = options.content ?? ''
    this.revision = options.revision ?? 0
  }

  setContent(content: string): void {
    this.content = content
  }

  setRevision(revision: number): void {
    this.revision = revision
  }

  /** Any op applied to the displayed text drags remote carets and the undo/redo stacks along with it. */
  onContentShift(applied: OperationSequence): void {
    for (const [userId, cursor] of this.remoteCursors) {
      this.remoteCursors.set(userId, this.clamp(transformCursor(cursor, applied, 'right')))
    }
    this.undoStack = this.undoStack.map((entry) => transformSequence(entry, applied).aPrime)
    this.redoStack = this.redoStack.map((entry) => transformSequence(entry, applied).aPrime)
  }

  send(ops: OperationSequence, baseRevision: number, clientOpId: string): void {
    // Any live transmission of the pending op satisfies the reconnect retransmit.
    this.needsRetransmit = false
    this.options.send(ops, baseRevision, clientOpId)
  }

  newClientOpId(): string {
    return this.options.newClientOpId()
  }

  localEdit(op: Operation): void {
    this.recordForUndo(op)
    this.state = this.state.applyLocal(this, op)
    this.options.onChange()
  }

  /** Reports the local caret to the server, tagged with the revision its positions are measured in. */
  localCursor(anchor: number, head: number): void {
    this.options.sendCursor?.(anchor, head, this.revision)
  }

  onSync(content: string, revision: number, participants: readonly RemoteParticipant[] = []): void {
    this.content = content
    this.revision = revision
    this.state = new Synchronized()
    this.remoteCursors.clear()
    this.participants.clear()
    for (const participant of participants) {
      this.participants.set(participant.userId, participant)
    }
    this.options.onChange()
  }

  /** Maps a caret from the server's frame into the display frame by replaying pending local ops. */
  onRemoteCursor(userId: string, anchor: number, head: number): void {
    const display = transformCursor({ anchor, head }, this.state.pendingOps(), 'right')
    this.remoteCursors.set(userId, this.clamp(display))
    this.options.onChange()
  }

  onPresence(joined: readonly RemoteParticipant[], left: readonly string[]): void {
    for (const participant of joined) {
      this.participants.set(participant.userId, participant)
    }
    for (const userId of left) {
      this.participants.delete(userId)
      this.remoteCursors.delete(userId)
    }
    this.options.onChange()
  }

  private clamp(cursor: Cursor): Cursor {
    const max = this.content.length
    return { anchor: Math.min(cursor.anchor, max), head: Math.min(cursor.head, max) }
  }

  onServerOp(ops: OperationSequence, revision: number, clientOpId: string): void {
    if (clientOpId === this.state.pendingClientOpId) {
      this.state = this.state.applyAck(this, revision)
    } else if (revision > this.revision) {
      this.sealOpenUnit() // a remote edit ends the current typing run
      this.state = this.state.applyRemote(this, ops, revision)
    } else {
      // Already applied — a reconnect replay overlapping ops delivered live. Ignore the duplicate.
      return
    }
    this.options.onChange()
  }

  // ---- undo / redo: selective (per-user), stacks kept in the live frame ----

  undo(): void {
    this.sealOpenUnit()
    const inverse = this.undoStack.pop()
    if (!inverse) return
    const redoEntry = invertSequence(inverse, this.content)
    this.applyInternal(inverse)
    this.redoStack.push(redoEntry)
    this.options.onChange()
  }

  redo(): void {
    this.sealOpenUnit()
    const forward = this.redoStack.pop()
    if (!forward) return
    const undoEntry = invertSequence(forward, this.content)
    this.applyInternal(forward)
    this.undoStack.push(undoEntry)
    this.options.onChange()
  }

  canUndo(): boolean {
    return this.undoStack.length > 0 || this.openUnit !== null
  }

  canRedo(): boolean {
    return this.redoStack.length > 0
  }

  /** Applies a sequence through the state machine as a normal edit, without recording it for undo. */
  private applyInternal(sequence: OperationSequence): void {
    for (const op of sequence) {
      this.state = this.state.applyLocal(this, op)
    }
  }

  /** Folds a user edit into the open undo unit, or starts a new one; a fresh edit discards redo. */
  private recordForUndo(op: Operation): void {
    if (op.kind === 'noop') return
    this.redoStack = []
    if (this.openUnit && this.openUnit.kind === op.kind && continues(this.openUnit, op)) {
      this.openUnit.forward.push(op)
    } else {
      this.sealOpenUnit()
      this.openUnit = { forward: [op], before: this.content, kind: op.kind }
    }
  }

  private sealOpenUnit(): void {
    if (!this.openUnit) return
    const inverse = invertSequence(this.openUnit.forward, this.openUnit.before)
    if (inverse.length > 0) {
      this.undoStack.push(inverse)
    }
    this.openUnit = null
  }

  /** Marks the pending op for retransmission; called when the socket reconnects, before replay. */
  beginResume(): void {
    this.needsRetransmit = true
  }

  /** The revision to catch up from — what the client last saw from the server. */
  resumePoint(): number {
    return this.revision
  }

  /** Replay is complete: re-send the pending op if it was never acknowledged during catch-up. */
  onResumed(revision: number): void {
    if (revision > this.revision) {
      this.revision = revision
    }
    if (this.needsRetransmit) {
      this.state.resend(this)
      this.needsRetransmit = false
    }
    this.options.onChange()
  }

  stateName(): string {
    return this.state.constructor.name
  }
}

/** Whether `op` continues an uninterrupted typing run: adjacent inserts, or adjacent deletes/backspaces. */
function continues(unit: UndoUnit, op: Operation): boolean {
  const last = unit.forward[unit.forward.length - 1]
  if (last.kind === 'insert' && op.kind === 'insert') {
    return op.position === last.position + last.text.length
  }
  if (last.kind === 'delete' && op.kind === 'delete') {
    return op.position === last.position || op.position + op.length === last.position
  }
  return false
}
