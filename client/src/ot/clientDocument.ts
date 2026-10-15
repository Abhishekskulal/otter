import type { Operation, OperationSequence } from './operation'
import { type DocumentContext, type OtState, Synchronized } from './states'

export interface ClientDocumentOptions {
  send: (ops: OperationSequence, baseRevision: number, clientOpId: string) => void
  onChange: () => void
  newClientOpId: () => string
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

  onSync(content: string, revision: number): void {
    this.content = content
    this.revision = revision
    this.state = new Synchronized()
    this.options.onChange()
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
