import { useEffect, useState } from 'react'
import { createDocument, type DocumentSummary, listDocuments } from '../api/documents'

function when(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}

export function DocumentList({ onOpen }: { onOpen: (id: string) => void }) {
  const [docs, setDocs] = useState<DocumentSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    listDocuments()
      .then(setDocs)
      .catch(() => setError('Could not load documents. Is the backend running?'))
  }, [])

  const create = async () => {
    setCreating(true)
    setError(null)
    try {
      const doc = await createDocument()
      onOpen(doc.id)
    } catch {
      setError('Could not create a document.')
      setCreating(false)
    }
  }

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', maxWidth: 720, margin: '2rem auto', padding: '0 1rem' }}>
      <header style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
        <h1 style={{ margin: 0 }}>
          Otter <small style={{ fontWeight: 400, color: '#888' }}>documents</small>
        </h1>
        <button
          onClick={create}
          disabled={creating}
          style={{ background: '#0c8d7e', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 14, cursor: creating ? 'default' : 'pointer', opacity: creating ? 0.6 : 1 }}
        >
          {creating ? 'Creating…' : 'New document'}
        </button>
      </header>

      {error && <p style={{ color: '#b23', fontSize: 14 }}>{error}</p>}

      {docs === null && !error && <p style={{ color: '#888' }}>Loading…</p>}

      {docs !== null && docs.length === 0 && (
        <p style={{ color: '#888' }}>No documents yet. Create one to get started.</p>
      )}

      {docs !== null && docs.length > 0 && (
        <ul style={{ listStyle: 'none', padding: 0, margin: '16px 0', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {docs.map((doc) => (
            <li key={doc.id}>
              <button
                onClick={() => onOpen(doc.id)}
                style={{ width: '100%', textAlign: 'left', background: 'transparent', border: '1px solid #e0e4e3', borderRadius: 10, padding: '12px 14px', cursor: 'pointer', display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}
              >
                <span style={{ fontSize: 15, fontWeight: 500 }}>{doc.title}</span>
                <span style={{ fontSize: 12, color: '#888', whiteSpace: 'nowrap' }}>
                  rev {doc.revision} · {when(doc.updatedAt)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
