import { describe, expect, it } from 'vitest'
import { ClientDocument } from './clientDocument'
import { insert } from './operation'
import type { OperationSequence } from './operation'

interface SentOp {
  ops: OperationSequence
  baseRevision: number
  clientOpId: string
}

function newDoc(content: string, revision: number) {
  const sends: SentOp[] = []
  let counter = 0
  const doc = new ClientDocument({
    send: (ops, baseRevision, clientOpId) => sends.push({ ops, baseRevision, clientOpId }),
    onChange: () => {},
    newClientOpId: () => `op${++counter}`,
    content,
    revision,
  })
  return { doc, sends }
}

describe('reconnection', () => {
  it('re-sends an in-flight op the server never received', () => {
    const { doc, sends } = newDoc('', 0)
    doc.localEdit(insert(0, 'hi')) // send #1, clientOpId op1
    doc.beginResume()
    doc.onResumed(0) // catch-up replayed nothing; the op was never acknowledged
    expect(sends).toHaveLength(2)
    expect(sends[1]).toMatchObject({ clientOpId: 'op1', baseRevision: 0 })
  })

  it('does not re-send an in-flight op that was acknowledged during replay', () => {
    const { doc, sends } = newDoc('', 0)
    doc.localEdit(insert(0, 'hi')) // send #1, op1
    doc.beginResume()
    doc.onServerOp([insert(0, 'hi')], 1, 'op1') // replay of our own commit = our ack
    doc.onResumed(1)
    expect(sends).toHaveLength(1)
    expect(doc.stateName()).toBe('Synchronized')
    expect(doc.revision).toBe(1)
  })

  it('sends the buffer exactly once when the in-flight op is acked mid-replay', () => {
    const { doc, sends } = newDoc('', 0)
    doc.localEdit(insert(0, 'a')) // send #1 op1 (in flight)
    doc.localEdit(insert(1, 'b')) // buffered
    doc.beginResume()
    doc.onServerOp([insert(0, 'a')], 1, 'op1') // ack in flight -> buffer sent (send #2)
    doc.onResumed(1)
    expect(sends).toHaveLength(2) // buffer not double-sent
    expect(sends[1].clientOpId).toBe('op2')
    expect(doc.stateName()).toBe('AwaitingAck')
  })

  it('ignores a replayed op that was already applied live', () => {
    const { doc } = newDoc('hello', 5)
    doc.onServerOp([insert(0, 'X')], 3, 'someone') // revision 3 <= 5, already applied
    expect(doc.content).toBe('hello')
    expect(doc.revision).toBe(5)
  })
})
