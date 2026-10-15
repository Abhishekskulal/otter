import {
  type Delete,
  type Insert,
  type Operation,
  type OperationSequence,
  del,
  insert,
  noop,
} from './operation'

export function apply(state: string, op: Operation): string {
  switch (op.kind) {
    case 'noop':
      return state
    case 'insert':
      if (op.position > state.length) {
        throw new Error(`Insert out of bounds: ${op.position} for state length ${state.length}`)
      }
      return state.slice(0, op.position) + op.text + state.slice(op.position)
    case 'delete':
      if (op.position > state.length || op.length > state.length - op.position) {
        throw new Error(`Delete out of bounds: ${op.position},${op.length} for length ${state.length}`)
      }
      return state.slice(0, op.position) + state.slice(op.position + op.length)
  }
}

export function applySequence(state: string, sequence: OperationSequence): string {
  let result = state
  for (const op of sequence) {
    result = apply(result, op)
  }
  return result
}

export interface TransformResult {
  readonly aPrime: Operation
  readonly bPrime: Operation
}

export function transform(a: Operation, b: Operation): TransformResult {
  if (a.kind === 'noop' || b.kind === 'noop') {
    return { aPrime: a, bPrime: b }
  }
  if (a.kind === 'insert' && b.kind === 'insert') {
    return insertInsert(a, b)
  }
  if (a.kind === 'insert' && b.kind === 'delete') {
    return insertDelete(a, b)
  }
  if (a.kind === 'delete' && b.kind === 'insert') {
    return deleteInsert(a, b)
  }
  return deleteDelete(a as Delete, b as Delete)
}

function insertInsert(a: Insert, b: Insert): TransformResult {
  if (a.position <= b.position) {
    return { aPrime: a, bPrime: insert(b.position + a.text.length, b.text) }
  }
  return { aPrime: insert(a.position + b.text.length, a.text), bPrime: b }
}

function insertDelete(a: Insert, b: Delete): TransformResult {
  const insertPos = a.position
  const deleteStart = b.position
  const deleteEnd = b.position + b.length
  const insertLen = a.text.length

  if (insertPos <= deleteStart) {
    return { aPrime: a, bPrime: del(deleteStart + insertLen, b.length) }
  }
  if (insertPos >= deleteEnd) {
    return { aPrime: insert(insertPos - b.length, a.text), bPrime: b }
  }
  return { aPrime: noop(), bPrime: del(deleteStart, b.length + insertLen) }
}

function deleteInsert(a: Delete, b: Insert): TransformResult {
  const deleteStart = a.position
  const deleteEnd = a.position + a.length
  const insertPos = b.position
  const insertLen = b.text.length

  if (insertPos <= deleteStart) {
    return { aPrime: del(deleteStart + insertLen, a.length), bPrime: b }
  }
  if (insertPos >= deleteEnd) {
    return { aPrime: a, bPrime: insert(insertPos - a.length, b.text) }
  }
  return { aPrime: del(deleteStart, a.length + insertLen), bPrime: noop() }
}

function deleteDelete(a: Delete, b: Delete): TransformResult {
  const aStart = a.position
  const aEnd = a.position + a.length
  const bStart = b.position
  const bEnd = b.position + b.length

  const overlap = Math.max(0, Math.min(aEnd, bEnd) - Math.max(aStart, bStart))

  const aLength = a.length - overlap
  const bLength = b.length - overlap

  const aShift = Math.max(0, Math.min(bEnd, aStart) - bStart)
  const bShift = Math.max(0, Math.min(aEnd, bStart) - aStart)

  const aPrime = aLength === 0 ? noop() : del(aStart - aShift, aLength)
  const bPrime = bLength === 0 ? noop() : del(bStart - bShift, bLength)

  return { aPrime, bPrime }
}

export interface SequenceTransformResult {
  readonly aPrime: OperationSequence
  readonly bPrime: OperationSequence
}

export function transformSequence(
  a: OperationSequence,
  b: OperationSequence,
): SequenceTransformResult {
  const aPrime: Operation[] = []
  let remainingB: OperationSequence = b
  for (const op of a) {
    let opPrime = op
    const bNext: Operation[] = []
    for (const bOp of remainingB) {
      const step = transform(opPrime, bOp)
      opPrime = step.aPrime
      bNext.push(step.bPrime)
    }
    aPrime.push(opPrime)
    remainingB = bNext
  }
  return { aPrime, bPrime: remainingB }
}

export function compose(sequence: OperationSequence, op: Operation): OperationSequence {
  return [...sequence, op].filter((candidate) => candidate.kind !== 'noop')
}
