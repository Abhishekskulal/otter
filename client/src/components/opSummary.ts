import type { Operation, OperationSequence } from '../ot/operation'

function quote(text: string): string {
  const trimmed = text.length > 14 ? text.slice(0, 14) + '…' : text
  return `"${trimmed.replace(/\n/g, '\\n')}"`
}

function one(op: Operation): string {
  switch (op.kind) {
    case 'insert':
      return `insert ${quote(op.text)} @${op.position}`
    case 'delete':
      return `delete ${op.length} @${op.position}`
    case 'noop':
      return 'noop'
  }
}

/** A compact, human-readable summary of a committed operation for the revision log. */
export function opSummary(ops: OperationSequence): string {
  return ops.length === 0 ? 'noop' : ops.map(one).join(' · ')
}
