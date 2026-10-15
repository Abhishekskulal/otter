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

interface ResumedMessage {
  type: 'resumed'
  documentId: string
  revision: number
}

interface ErrorMessage {
  type: 'error'
  documentId: string
  code: string
  message: string
}

type ServerMessage =
  | SyncMessage
  | OpMessage
  | CursorMessage
  | PresenceMessage
  | ResumedMessage
  | ErrorMessage

export interface OtConnectionHandlers {
  onStatus: (status: ConnectionStatus) => void
  onSync: (content: string, revision: number, participants: RemoteParticipant[]) => void
  onOp: (ops: Operation[], revision: number, clientOpId: string, authorId: string) => void
  onCursor: (userId: string, anchor: number, head: number) => void
  onPresence: (joined: RemoteParticipant[], left: string[]) => void
  onResumed: (revision: number) => void
  onError: (code: string, message: string) => void
  /** Called when the socket reopens after a drop; returns the revision to catch up from. */
  onReconnect: () => number
}

export interface OtConnection {
  sendOp: (ops: OperationSequence, baseRevision: number, clientOpId: string) => void
  sendCursor: (anchor: number, head: number, baseRevision: number) => void
  close: () => void
}

const INITIAL_BACKOFF_MS = 500
const MAX_BACKOFF_MS = 10_000

export function openOtConnection(
  url: string,
  documentId: string,
  handlers: OtConnectionHandlers,
): OtConnection {
  let socket: WebSocket
  let intentionallyClosed = false
  let firstConnect = true
  let backoff = INITIAL_BACKOFF_MS

  function connect(): void {
    socket = new WebSocket(url)
    handlers.onStatus('connecting')

    socket.addEventListener('open', () => {
      handlers.onStatus('open')
      backoff = INITIAL_BACKOFF_MS
      if (firstConnect) {
        firstConnect = false
        socket.send(JSON.stringify({ type: 'join', documentId }))
      } else {
        const afterRevision = handlers.onReconnect()
        socket.send(JSON.stringify({ type: 'resume', documentId, afterRevision }))
      }
    })

    socket.addEventListener('close', () => {
      handlers.onStatus('closed')
      if (!intentionallyClosed) {
        setTimeout(connect, backoff)
        backoff = Math.min(backoff * 2, MAX_BACKOFF_MS)
      }
    })

    socket.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data)) as ServerMessage
      switch (message.type) {
        case 'sync':
          handlers.onSync(message.content, message.revision, message.participants)
          break
        case 'op':
          handlers.onOp(message.ops, message.revision, message.clientOpId, message.authorId)
          break
        case 'cursor':
          handlers.onCursor(message.userId, message.anchor, message.head)
          break
        case 'presence':
          handlers.onPresence(message.joined, message.left)
          break
        case 'resumed':
          handlers.onResumed(message.revision)
          break
        case 'error':
          handlers.onError(message.code, message.message)
          break
      }
    })
  }

  connect()

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
      intentionallyClosed = true
      socket.close()
    },
  }
}
