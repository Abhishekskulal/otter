import { describe, expect, it } from 'vitest'
import { apply, applySequence, transform, transformSequence } from './engine'
import { randomOp, randomSequence, randomString, rng } from './randomOps'

describe('ot engine port', () => {
  it('satisfies single-operation TP1', () => {
    const next = rng(0x5eed)
    for (let i = 0; i < 20000; i++) {
      const state = randomString(next)
      const a = randomOp(next, state)
      const b = randomOp(next, state)
      const { aPrime, bPrime } = transform(a, b)
      expect(apply(apply(state, a), bPrime)).toBe(apply(apply(state, b), aPrime))
    }
  })

  it('satisfies sequence TP1', () => {
    const next = rng(0x1337)
    for (let i = 0; i < 20000; i++) {
      const state = randomString(next)
      const a = randomSequence(next, state)
      const b = randomSequence(next, state)
      const { aPrime, bPrime } = transformSequence(a, b)
      expect(applySequence(applySequence(state, a), bPrime)).toBe(
        applySequence(applySequence(state, b), aPrime),
      )
    }
  })
})
