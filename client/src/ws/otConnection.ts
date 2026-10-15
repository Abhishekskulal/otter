import type { Operation, OperationSequence } from '../ot/operation'

export type ConnectionStatus = 'connecting' | 'open' | 'closed'

interface SyncMessage {
  type: 'sync'
  documentId: string
  revision: number
  content: string
}

interface OpMessage {
  type: 'op'
  documentId: string
  revision: number
  clientOpId: string
  authorId: string
  ops: Operation[]
}

interface ErrorMessage {
  type: 'error'
  documentId: string
  code: string
  message: string
}

type ServerMessage = SyncMessage | OpMessage | ErrorMessage

export interface OtConnectionHandlers {
  onStatus: (status: ConnectionStatus) => void
  onSync: (content: string, revision: number) => void
  onOp: (ops: Operation[], revision: number, clientOpId: string) => void
  onError: (code: string, message: string) => void
}

export interface OtConnection {
  sendOp: (ops: OperationSequence, baseRevision: number, clientOpId: string) => void
  close: () => void
}

export function openOtConnection(
  url: string,
  documentId: string,
  handlers: OtConnectionHandlers,
): OtConnection {
  const socket = new WebSocket(url)
  handlers.onStatus('connecting')

  socket.addEventListener('open', () => {
    handlers.onStatus('open')
    socket.send(JSON.stringify({ type: 'join', documentId }))
  })
  socket.addEventListener('close', () => handlers.onStatus('closed'))
  socket.addEventListener('message', (event) => {
    const message = JSON.parse(String(event.data)) as ServerMessage
    switch (message.type) {
      case 'sync':
        handlers.onSync(message.content, message.revision)
        break
      case 'op':
        handlers.onOp(message.ops, message.revision, message.clientOpId)
        break
      case 'error':
        handlers.onError(message.code, message.message)
        break
    }
  })

  return {
    sendOp(ops, baseRevision, clientOpId) {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'op', documentId, clientOpId, baseRevision, ops }))
      }
    },
    close() {
      socket.close()
    },
  }
}
