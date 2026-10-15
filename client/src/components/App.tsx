import { useEffect, useRef, useState } from 'react'
import { ClientDocument } from '../ot/clientDocument'
import { diffToOps } from '../text/diff'
import { type ConnectionStatus, type OtConnection, openOtConnection } from '../ws/otConnection'

const DOCUMENT_ID = '00000000-0000-0000-0000-000000000001'

function wsUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

export function App() {
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const [content, setContent] = useState('')
  const [stateName, setStateName] = useState('Synchronized')
  const docRef = useRef<ClientDocument | null>(null)
  const connRef = useRef<OtConnection | null>(null)

  useEffect(() => {
    let conn: OtConnection | null = null

    const doc = new ClientDocument({
      send: (ops, baseRevision, clientOpId) => conn?.sendOp(ops, baseRevision, clientOpId),
      onChange: () => {
        setContent(doc.content)
        setStateName(doc.stateName())
      },
      newClientOpId: () => crypto.randomUUID(),
    })
    docRef.current = doc

    conn = openOtConnection(wsUrl(), DOCUMENT_ID, {
      onStatus: setStatus,
      onSync: (text, revision) => doc.onSync(text, revision),
      onOp: (ops, revision, clientOpId) => doc.onServerOp(ops, revision, clientOpId),
      onError: (code, message) => console.error(`otter error ${code}: ${message}`),
    })
    connRef.current = conn

    return () => conn?.close()
  }, [])

  const onTextareaChange = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const doc = docRef.current
    if (!doc) return
    for (const op of diffToOps(doc.content, event.target.value)) {
      doc.localEdit(op)
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
      <textarea
        value={content}
        onChange={onTextareaChange}
        spellCheck={false}
        style={{ width: '100%', height: 320, padding: 12, fontFamily: 'ui-monospace, monospace', fontSize: 14 }}
      />
    </main>
  )
}
