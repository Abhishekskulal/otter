import { apply, applySequence, compose, transformSequence } from './engine'
import type { Operation, OperationSequence } from './operation'

/**
 * The context a state acts on: the local document and the outbound transport. States own the
 * in-flight and buffered operations themselves; the context owns the text and the revision.
 */
export interface DocumentContext {
  readonly content: string
  readonly revision: number
  setContent(content: string): void
  setRevision(revision: number): void
  send(ops: OperationSequence, baseRevision: number, clientOpId: string): void
  newClientOpId(): string
  /** Notifies the context that `applied` was just applied to the displayed text, so carets can follow. */
  shiftCursors(applied: OperationSequence): void
}

export interface OtState {
  /** The client-op id of the operation in flight, or null when nothing is outstanding. */
  readonly pendingClientOpId: string | null
  /** Local edits not yet in the server's state, in display order — what maps server frame to display frame. */
  pendingOps(): OperationSequence
  applyLocal(ctx: DocumentContext, op: Operation): OtState
  applyRemote(ctx: DocumentContext, ops: OperationSequence, revision: number): OtState
  applyAck(ctx: DocumentContext, revision: number): OtState
}

export class Synchronized implements OtState {
  readonly pendingClientOpId = null

  pendingOps(): OperationSequence {
    return []
  }

  applyLocal(ctx: DocumentContext, op: Operation): OtState {
    ctx.setContent(apply(ctx.content, op))
    ctx.shiftCursors([op])
    const clientOpId = ctx.newClientOpId()
    const inFlight: OperationSequence = [op]
    ctx.send(inFlight, ctx.revision, clientOpId)
    return new AwaitingAck(inFlight, clientOpId)
  }

  applyRemote(ctx: DocumentContext, ops: OperationSequence, revision: number): OtState {
    ctx.setContent(applySequence(ctx.content, ops))
    ctx.shiftCursors(ops)
    ctx.setRevision(revision)
    return this
  }

  applyAck(): OtState {
    throw new Error('Acknowledgement received while synchronized with nothing in flight')
  }
}

export class AwaitingAck implements OtState {
  constructor(
    private readonly inFlight: OperationSequence,
    readonly pendingClientOpId: string,
  ) {}

  pendingOps(): OperationSequence {
    return this.inFlight
  }

  applyLocal(ctx: DocumentContext, op: Operation): OtState {
    ctx.setContent(apply(ctx.content, op))
    ctx.shiftCursors([op])
    return new AwaitingWithBuffer(this.inFlight, this.pendingClientOpId, [op])
  }

  applyRemote(ctx: DocumentContext, ops: OperationSequence, revision: number): OtState {
    const { aPrime, bPrime } = transformSequence(this.inFlight, ops)
    ctx.setContent(applySequence(ctx.content, bPrime))
    ctx.shiftCursors(bPrime)
    ctx.setRevision(revision)
    return new AwaitingAck(aPrime, this.pendingClientOpId)
  }

  applyAck(ctx: DocumentContext, revision: number): OtState {
    ctx.setRevision(revision)
    return new Synchronized()
  }
}

export class AwaitingWithBuffer implements OtState {
  constructor(
    private readonly inFlight: OperationSequence,
    readonly pendingClientOpId: string,
    private readonly buffer: OperationSequence,
  ) {}

  pendingOps(): OperationSequence {
    return [...this.inFlight, ...this.buffer]
  }

  applyLocal(ctx: DocumentContext, op: Operation): OtState {
    ctx.setContent(apply(ctx.content, op))
    ctx.shiftCursors([op])
    return new AwaitingWithBuffer(this.inFlight, this.pendingClientOpId, compose(this.buffer, op))
  }

  applyRemote(ctx: DocumentContext, ops: OperationSequence, revision: number): OtState {
    const againstInFlight = transformSequence(this.inFlight, ops)
    const againstBuffer = transformSequence(this.buffer, againstInFlight.bPrime)
    ctx.setContent(applySequence(ctx.content, againstBuffer.bPrime))
    ctx.shiftCursors(againstBuffer.bPrime)
    ctx.setRevision(revision)
    return new AwaitingWithBuffer(
      againstInFlight.aPrime,
      this.pendingClientOpId,
      againstBuffer.aPrime,
    )
  }

  applyAck(ctx: DocumentContext, revision: number): OtState {
    ctx.setRevision(revision)
    const clientOpId = ctx.newClientOpId()
    ctx.send(this.buffer, revision, clientOpId)
    return new AwaitingAck(this.buffer, clientOpId)
  }
}
