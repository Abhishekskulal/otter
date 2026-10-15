import { type Cursor, transformCursor } from './engine'
import type { Operation, OperationSequence } from './operation'
import { type DocumentContext, type OtState, Synchronized } from './states'

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

  /** Any op applied to the displayed text drags every remote caret along with it. */
  shiftCursors(applied: OperationSequence): void {
    for (const [userId, cursor] of this.remoteCursors) {
      this.remoteCursors.set(userId, this.clamp(transformCursor(cursor, applied, 'right')))
    }
  }

  send(ops: OperationSequence, baseRevision: number, clientOpId: string): void {
    this.options.send(ops, baseRevision, clientOpId)
  }

  newClientOpId(): string {
    return this.options.newClientOpId()
  }

  localEdit(op: Operation): void {
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
    } else {
      this.state = this.state.applyRemote(this, ops, revision)
    }
    this.options.onChange()
  }

  stateName(): string {
    return this.state.constructor.name
  }
}
