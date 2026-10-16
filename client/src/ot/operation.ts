export type Insert = { readonly kind: 'insert'; readonly position: number; readonly text: string }
export type Delete = { readonly kind: 'delete'; readonly position: number; readonly length: number }
export type NoOp = { readonly kind: 'noop' }

export type Operation = Insert | Delete | NoOp
export type OperationSequence = readonly Operation[]

export function insert(position: number, text: string): Insert {
  return { kind: 'insert', position, text }
}

export function del(position: number, length: number): Delete {
  return { kind: 'delete', position, length }
}

export function noop(): NoOp {
  return { kind: 'noop' }
}
