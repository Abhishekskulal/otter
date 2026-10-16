import { useEffect, useState } from 'react'
import { createDocument, type DocumentSummary, listDocuments } from '../api/documents'
import { displayName, setDisplayName } from '../identity'
import { ThemeToggle } from './theme'

function when(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString()
}

export function DocumentList({ onOpen }: { onOpen: (id: string) => void }) {
  const [docs, setDocs] = useState<DocumentSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState(displayName())

  useEffect(() => {
    listDocuments()
      .then(setDocs)
      .catch(() => setError('Could not load documents. Is the backend running?'))
  }, [])

  const create = async () => {
    setCreating(true)
    setError(null)
    try {
      onOpen((await createDocument()).id)
    } catch {
      setError('Could not create a document.')
      setCreating(false)
    }
  }

  return (
    <div className="wrap">
      <div className="bar">
        <h1 className="brand">otter<small>documents</small></h1>
        <span className="spacer"></span>
        <label className="whoami">
          You
          <input
            className="name-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={() => setDisplayName(name)}
            aria-label="Your display name"
          />
        </label>
        <ThemeToggle />
        <button className="btn-primary" onClick={create} disabled={creating} type="button">
          {creating ? 'Creating…' : 'New document'}
        </button>
      </div>

      {error && <p className="error-note">{error}</p>}
      {docs === null && !error && <p className="empty-note">Loading…</p>}
      {docs !== null && docs.length === 0 && <p className="empty-note">No documents yet. Create one to get started.</p>}

      {docs !== null && docs.length > 0 && (
        <ul className="doclist">
          {docs.map((doc) => (
            <li key={doc.id}>
              <button onClick={() => onOpen(doc.id)} type="button">
                <span className="dtitle">{doc.title}</span>
                <span className="dmeta">rev {doc.revision} · {when(doc.updatedAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
