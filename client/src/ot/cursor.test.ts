import { describe, expect, it } from 'vitest'
import { ClientDocument, type ClientDocumentOptions } from './clientDocument'
import { del, insert } from './operation'

function newDoc(content: string, revision: number, overrides: Partial<ClientDocumentOptions> = {}) {
  return new ClientDocument({
    send: () => {},
    onChange: () => {},
    newClientOpId: () => 'op',
    content,
    revision,
    ...overrides,
  })
}

describe('remote cursor tracking', () => {
  it('shifts a remote caret when the local user inserts before it', () => {
    const doc = newDoc('hello', 1)
    doc.onRemoteCursor('u1', 5, 5)
    doc.localEdit(insert(0, 'XYZ'))
    expect(doc.remoteCursors.get('u1')).toEqual({ anchor: 8, head: 8 })
  })

  it('shifts a remote caret when a remote edit arrives', () => {
    const doc = newDoc('hello', 1)
    doc.onRemoteCursor('u1', 2, 2)
    doc.onServerOp([insert(0, 'AB')], 2, 'someone-else')
    expect(doc.remoteCursors.get('u1')).toEqual({ anchor: 4, head: 4 })
  })

  it('collapses a remote caret inside a deleted range to the cut point', () => {
    const doc = newDoc('abcdef', 1)
    doc.onRemoteCursor('u1', 4, 4)
    doc.localEdit(del(2, 3)) // removes "cde"
    expect(doc.remoteCursors.get('u1')).toEqual({ anchor: 2, head: 2 })
  })

  it('maps an incoming caret past the unacknowledged local edits', () => {
    const doc = newDoc('hello', 1)
    doc.localEdit(insert(0, 'XY')) // in flight, not yet acked; display is "XYhello"
    doc.onRemoteCursor('u1', 5, 5) // server-frame caret at end of "hello"
    expect(doc.remoteCursors.get('u1')).toEqual({ anchor: 7, head: 7 })
  })

  it('tracks presence join and departure', () => {
    const doc = newDoc('', 0)
    doc.onPresence([{ userId: 'u1', displayName: 'Anon-1', colour: '#fff' }], [])
    expect(doc.participants.get('u1')?.displayName).toBe('Anon-1')
    doc.onRemoteCursor('u1', 0, 0)
    doc.onPresence([], ['u1'])
    expect(doc.participants.has('u1')).toBe(false)
    expect(doc.remoteCursors.has('u1')).toBe(false)
  })
})
