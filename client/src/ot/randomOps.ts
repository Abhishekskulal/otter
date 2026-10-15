import { apply } from './engine'
import { type Operation, del, insert, noop } from './operation'

export function rng(seed: number): () => number {
  let state = seed
  return () => {
    state |= 0
    state = (state + 0x6d2b79f5) | 0
    let t = Math.imul(state ^ (state >>> 15), 1 | state)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function randInt(next: () => number, bound: number): number {
  return Math.floor(next() * bound)
}

export function randomString(next: () => number): string {
  const length = randInt(next, 13)
  let out = ''
  for (let i = 0; i < length; i++) {
    out += String.fromCharCode(97 + randInt(next, 26))
  }
  return out
}

function randomText(next: () => number): string {
  const length = 1 + randInt(next, 3)
  let out = ''
  for (let i = 0; i < length; i++) {
    out += String.fromCharCode(97 + randInt(next, 26))
  }
  return out
}

export function randomOp(next: () => number, state: string): Operation {
  const kind = randInt(next, 3)
  if (kind === 0) {
    return noop()
  }
  if (kind === 1 || state.length === 0) {
    return insert(randInt(next, state.length + 1), randomText(next))
  }
  const position = randInt(next, state.length)
  return del(position, 1 + randInt(next, state.length - position))
}

export function randomSequence(next: () => number, state: string): Operation[] {
  const count = randInt(next, 5)
  const ops: Operation[] = []
  let current = state
  for (let i = 0; i < count; i++) {
    const op = randomOp(next, current)
    ops.push(op)
    current = apply(current, op)
  }
  return ops
}
