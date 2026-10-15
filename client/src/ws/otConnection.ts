import type { RemoteParticipant } from '../ot/clientDocument'
import type { Operation, OperationSequence } from '../ot/operation'

export type ConnectionStatus = 'connecting' | 'open' | 'closed'

interface SyncMessage {
  type: 'sync'
  documentId: string
  revision: number
  content: string
  participants: RemoteParticipant[]
}

interface OpMessage {
  type: 'op'
  documentId: string
  revision: number
  clientOpId: string
  authorId: string
  ops: Operation[]
}

interface CursorMessage {
  type: 'cursor'
  documentId: string
  userId: string
  revision: number
  anchor: number
  head: number
}

interface PresenceMessage {
  type: 'presence'
  documentId: string
  joined: RemoteParticipant[]
  left: string[]
}

interface ErrorMessage {
  type: 'error'
  documentId: string
  code: string
  message: string
}

type ServerMessage = SyncMessage | OpMessage | CursorMessage | PresenceMessage | ErrorMessage

export interface OtConnectionHandlers {
  onStatus: (status: ConnectionStatus) => void
  onSync: (content: string, revision: number, participants: RemoteParticipant[]) => void
  onOp: (ops: Operation[], revision: number, clientOpId: string) => void
  onCursor: (userId: string, anchor: number, head: number) => void
  onPresence: (joined: RemoteParticipant[], left: string[]) => void
  onError: (code: string, message: string) => void
}

export interface OtConnection {
  sendOp: (ops: OperationSequence, baseRevision: number, clientOpId: string) => void
  sendCursor: (anchor: number, head: number, baseRevision: number) => void
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
        handlers.onSync(message.content, message.revision, message.participants)
        break
      case 'op':
        handlers.onOp(message.ops, message.revision, message.clientOpId)
        break
      case 'cursor':
        handlers.onCursor(message.userId, message.anchor, message.head)
        break
      case 'presence':
        handlers.onPresence(message.joined, message.left)
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
    sendCursor(anchor, head, baseRevision) {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'cursor', documentId, baseRevision, anchor, head }))
      }
    },
    close() {
      socket.close()
    },
  }
}
