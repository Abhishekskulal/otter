import { useEffect, useRef, useState } from 'react'
import { ClientDocument, type RemoteParticipant } from '../ot/clientDocument'
import { diffToOps } from '../text/diff'
import { type ConnectionStatus, type OtConnection, openOtConnection } from '../ws/otConnection'

const DOCUMENT_ID = '00000000-0000-0000-0000-000000000001'

interface Caret {
  readonly userId: string
  readonly offset: number
  readonly colour: string
  readonly label: string
}

const textBox: React.CSSProperties = {
  width: '100%',
  height: 320,
  padding: 12,
  margin: 0,
  border: '1px solid #ccc',
  boxSizing: 'border-box',
  fontFamily: 'ui-monospace, monospace',
  fontSize: 14,
  lineHeight: 1.5,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'break-word',
}

function wsUrl(): string {
  // A split deploy (static client on a CDN, backend on Render) sets this to the backend's wss URL.
  // Local dev and single-origin deploys fall back to the current origin, which Vite proxies to :8080.
  if (import.meta.env.VITE_WS_URL) {
    return import.meta.env.VITE_WS_URL
  }
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

function buildCarets(doc: ClientDocument): Caret[] {
  const carets: Caret[] = []
  for (const [userId, cursor] of doc.remoteCursors) {
    const who = doc.participants.get(userId)
    carets.push({
      userId,
      offset: cursor.head,
      colour: who?.colour ?? '#888',
      label: who?.displayName ?? userId.slice(0, 4),
    })
  }
  return carets.sort((a, b) => a.offset - b.offset)
}

export function App() {
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const [content, setContent] = useState('')
  const [stateName, setStateName] = useState('Synchronized')
  const [carets, setCarets] = useState<Caret[]>([])
  const [participants, setParticipants] = useState<RemoteParticipant[]>([])
  const docRef = useRef<ClientDocument | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement | null>(null)
  const mirrorRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    let conn: OtConnection | null = null

    const doc = new ClientDocument({
      send: (ops, baseRevision, clientOpId) => conn?.sendOp(ops, baseRevision, clientOpId),
      sendCursor: (anchor, head, baseRevision) => conn?.sendCursor(anchor, head, baseRevision),
      onChange: () => {
        setContent(doc.content)
        setStateName(doc.stateName())
        setCarets(buildCarets(doc))
        setParticipants([...doc.participants.values()])
      },
      newClientOpId: () => crypto.randomUUID(),
    })
    docRef.current = doc

    conn = openOtConnection(wsUrl(), DOCUMENT_ID, {
      onStatus: setStatus,
      onSync: (text, revision, members) => doc.onSync(text, revision, members),
      onOp: (ops, revision, clientOpId) => doc.onServerOp(ops, revision, clientOpId),
      onCursor: (userId, anchor, head) => doc.onRemoteCursor(userId, anchor, head),
      onPresence: (joined, left) => doc.onPresence(joined, left),
      onResumed: (revision) => doc.onResumed(revision),
      onReconnect: () => {
        doc.beginResume()
        return doc.resumePoint()
      },
      onError: (code, message) => console.error(`otter error ${code}: ${message}`),
    })

    return () => conn?.close()
  }, [])

  const sendCaret = () => {
    const doc = docRef.current
    const area = textareaRef.current
    if (doc && area) doc.localCursor(area.selectionStart, area.selectionEnd)
  }

  const onTextareaChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const doc = docRef.current
    if (!doc) return
    for (const op of diffToOps(doc.content, event.target.value)) {
      doc.localEdit(op)
    }
    sendCaret()
  }

  const syncScroll = () => {
    const area = textareaRef.current
    const mirror = mirrorRef.current
    if (area && mirror) {
      mirror.scrollTop = area.scrollTop
      mirror.scrollLeft = area.scrollLeft
    }
  }

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 720, margin: '2rem auto', padding: '0 1rem' }}>
      <h1>
        Otter <small style={{ fontWeight: 400, color: '#888' }}>collaborative editor</small>
      </h1>
      <p style={{ color: '#666' }}>
        Transport: <strong>{status}</strong> · Client state: <strong>{stateName}</strong>
      </p>
      {status !== 'open' && (
        <p style={{ background: '#fff4e5', border: '1px solid #f0c893', borderRadius: 6, padding: '8px 12px', fontSize: 13, color: '#8a5a00' }}>
          Connecting to the backend. The free-tier server sleeps when idle and can take 30–60 seconds to wake — this reconnects automatically.
        </p>
      )}
      <PresenceStrip participants={participants} />
      <div style={{ position: 'relative' }}>
        <div ref={mirrorRef} aria-hidden style={{ ...textBox, position: 'absolute', inset: 0, color: 'transparent', pointerEvents: 'none', overflow: 'hidden', borderColor: 'transparent' }}>
          <CaretLayer content={content} carets={carets} />
        </div>
        <textarea
          ref={textareaRef}
          value={content}
          onChange={onTextareaChange}
          onSelect={sendCaret}
          onKeyUp={sendCaret}
          onClick={sendCaret}
          onScroll={syncScroll}
          spellCheck={false}
          style={{ ...textBox, position: 'relative', background: 'transparent' }}
        />
      </div>
    </main>
  )
}

function CaretLayer({ content, carets }: { content: string; carets: Caret[] }) {
  const nodes: React.ReactNode[] = []
  let last = 0
  carets.forEach((caret, index) => {
    const at = Math.max(0, Math.min(caret.offset, content.length))
    nodes.push(content.slice(last, at))
    nodes.push(
      <span key={index} style={{ position: 'relative', borderLeft: `2px solid ${caret.colour}`, marginLeft: -1 }}>
        <span style={{ position: 'absolute', top: -14, left: -1, fontSize: 10, lineHeight: 1, color: '#fff', background: caret.colour, padding: '1px 3px', borderRadius: 3, whiteSpace: 'nowrap' }}>
          {caret.label}
        </span>
      </span>,
    )
    last = at
  })
  nodes.push(content.slice(last))
  return <>{nodes}</>
}

function PresenceStrip({ participants }: { participants: RemoteParticipant[] }) {
  if (participants.length === 0) {
    return <p style={{ color: '#aaa', fontSize: 13, minHeight: 20 }}>No one else here yet.</p>
  }
  return (
    <div style={{ display: 'flex', gap: 10, minHeight: 20, marginBottom: 6, flexWrap: 'wrap' }}>
      {participants.map((member) => (
        <span key={member.userId} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 13 }}>
          <span style={{ width: 10, height: 10, borderRadius: '50%', background: member.colour }} />
          {member.displayName}
        </span>
      ))}
    </div>
  )
}
