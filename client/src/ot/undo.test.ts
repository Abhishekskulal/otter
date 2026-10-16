import { describe, expect, it } from 'vitest'
import { ClientDocument } from './clientDocument'
import { del, insert } from './operation'

function newDoc(content: string, revision: number) {
  return new ClientDocument({
    send: () => {},
    onChange: () => {},
    newClientOpId: () => crypto.randomUUID(),
    content,
    revision,
  })
}

function type(doc: ClientDocument, text: string, at = 0) {
  for (let i = 0; i < text.length; i++) doc.localEdit(insert(at + i, text[i]))
}

describe('undo / redo', () => {
  it('coalesces a typing run into one undo', () => {
    const doc = newDoc('', 0)
    type(doc, 'hello')
    expect(doc.content).toBe('hello')
    doc.undo()
    expect(doc.content).toBe('')
  })

  it('redoes what it undid', () => {
    const doc = newDoc('', 0)
    type(doc, 'hello')
    doc.undo()
    doc.redo()
    expect(doc.content).toBe('hello')
  })

  it('restores text when undoing a delete', () => {
    const doc = newDoc('hello', 1)
    doc.localEdit(del(0, 5))
    expect(doc.content).toBe('')
    doc.undo()
    expect(doc.content).toBe('hello')
  })

  it('undoes only the local edit after a concurrent remote edit', () => {
    const doc = newDoc('', 0)
    doc.localEdit(insert(0, 'cat')) // local, in flight
    doc.onServerOp([insert(0, 'big ')], 1, 'someone-else') // remote insert arrives
    // Undo must remove "cat" in its shifted position, leaving the remote text intact.
    doc.undo()
    expect(doc.content).toBe('big ')
  })

  it('reports whether undo and redo are available', () => {
    const doc = newDoc('', 0)
    expect(doc.canUndo()).toBe(false)
    doc.localEdit(insert(0, 'a'))
    expect(doc.canUndo()).toBe(true)
    expect(doc.canRedo()).toBe(false)
    doc.undo()
    expect(doc.canUndo()).toBe(false)
    expect(doc.canRedo()).toBe(true)
  })
})
