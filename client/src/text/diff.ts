import { type Operation, del, insert } from '../ot/operation'

/**
 * Derives the operations that turn {@code oldText} into {@code newText} by isolating the single
 * changed span between the shared prefix and suffix. A pure insertion yields one insert, a pure
 * deletion one delete, and a replacement a delete followed by an insert.
 */
export function diffToOps(oldText: string, newText: string): Operation[] {
  if (oldText === newText) {
    return []
  }

  const maxPrefix = Math.min(oldText.length, newText.length)
  let prefix = 0
  while (prefix < maxPrefix && oldText[prefix] === newText[prefix]) {
    prefix++
  }

  let suffix = 0
  while (
    suffix < maxPrefix - prefix &&
    oldText[oldText.length - 1 - suffix] === newText[newText.length - 1 - suffix]
  ) {
    suffix++
  }

  const deleted = oldText.length - prefix - suffix
  const inserted = newText.slice(prefix, newText.length - suffix)

  const ops: Operation[] = []
  if (deleted > 0) {
    ops.push(del(prefix, deleted))
  }
  if (inserted.length > 0) {
    ops.push(insert(prefix, inserted))
  }
  return ops
}
