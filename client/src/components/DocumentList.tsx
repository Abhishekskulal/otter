import { useEffect, useState } from 'react'
import { createDocument, type DocumentSummary, listDocuments } from '../api/documents'
import { displayName, setDisplayName } from '../identity'
import { ThemeToggle } from './theme'

const ACCENTS = [
  '#0c8d7e', '#d8912a', '#8167cf', '#c0455e', '#3f8fd0',
  '#4aa564', '#d06a3f', '#5b8a8a', '#a8582f', '#6d6fcf',
]

function accent(id: string): string {
  let hash = 0
  for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) | 0
  return ACCENTS[Math.abs(hash) % ACCENTS.length]
}

function relativeTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const seconds = Math.floor((Date.now() - date.getTime()) / 1000)
  if (seconds < 45) return 'just now'
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days}d ago`
  return date.toLocaleDateString()
}

function greeting(): string {
  const hour = new Date().getHours()
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

function readView(): 'grid' | 'list' {
  try {
    return localStorage.getItem('otter-view') === 'list' ? 'list' : 'grid'
  } catch {
    return 'grid'
  }
}

export function DocumentList({ onOpen }: { onOpen: (id: string) => void }) {
  const [docs, setDocs] = useState<DocumentSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState(displayName())
  const [query, setQuery] = useState('')
  const [view, setView] = useState<'grid' | 'list'>(readView)

  useEffect(() => {
    listDocuments()
      .then(setDocs)
      .catch(() => setError('Could not load documents. Is the backend running?'))
  }, [])

  useEffect(() => {
    try {
      localStorage.setItem('otter-view', view)
    } catch {
      /* ignore */
    }
  }, [view])

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

  const filtered = (docs ?? []).filter((d) => d.title.toLowerCase().includes(query.toLowerCase()))

  return (
    <div className="wrap">
      <div className="bar">
        <h1 className="brand"><img className="brand-mark" src="/favicon.svg" alt="" width="30" height="30" />otter<small>collaborative editor</small></h1>
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
          {creating ? 'Creating…' : '+ New document'}
        </button>
      </div>

      <section className="hero">
        <h2 className="greeting">{greeting()}{name ? `, ${name}` : ''}</h2>
        <p className="hero-sub">
          Real-time collaborative editing on a hand-written operational-transformation engine. Create a
          document and share its link — anyone who opens it edits with you, live, and it all converges.
        </p>
      </section>

      <div className="home-head">
        <div className="home-sub">
          {docs === null ? 'loading…' : `${docs.length} document${docs.length === 1 ? '' : 's'}`}
        </div>
        <div className="toolbar">
          <div className="search">
            <span className="mag" aria-hidden>⌕</span>
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search documents" aria-label="Search documents" />
          </div>
          <div className="viewtoggle" role="group" aria-label="View">
            <button className={view === 'grid' ? 'on' : ''} onClick={() => setView('grid')} type="button" aria-label="Grid view">▦</button>
            <button className={view === 'list' ? 'on' : ''} onClick={() => setView('list')} type="button" aria-label="List view">☰</button>
          </div>
        </div>
      </div>

      {error && <p className="error-note">{error}</p>}
      {docs === null && !error && <p className="empty-note">Loading…</p>}

      {docs !== null && docs.length === 0 && (
        <div className="empty-state">
          <div className="empty-mark">✎</div>
          <h3>No documents yet</h3>
          <p>Create one and share the link — anyone who opens it edits with you in real time.</p>
          <button className="btn-primary" onClick={create} disabled={creating} type="button">
            {creating ? 'Creating…' : '+ New document'}
          </button>
        </div>
      )}

      {docs !== null && docs.length > 0 && filtered.length === 0 && (
        <p className="empty-note">No documents match “{query}”.</p>
      )}

      {filtered.length > 0 && view === 'grid' && (
        <div className="cardgrid">
          {filtered.map((doc) => (
            <button key={doc.id} className="card" onClick={() => onOpen(doc.id)} type="button">
              <span className="glyph" style={{ background: accent(doc.id) }}>{doc.title.charAt(0).toUpperCase() || '·'}</span>
              <span className="card-title">{doc.title}</span>
              <span className="card-meta">rev {doc.revision} · {relativeTime(doc.updatedAt)}</span>
            </button>
          ))}
        </div>
      )}

      {filtered.length > 0 && view === 'list' && (
        <ul className="doclist">
          {filtered.map((doc) => (
            <li key={doc.id}>
              <button onClick={() => onOpen(doc.id)} type="button">
                <span className="dtitle"><span className="dotmark" style={{ background: accent(doc.id) }}></span>{doc.title}</span>
                <span className="dmeta">rev {doc.revision} · {relativeTime(doc.updatedAt)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <footer className="home-footer">
        <span>otter · collaborative plain-text editing on operational transformation</span>
        <a href="https://github.com/Abhishekskulal/otter" target="_blank" rel="noreferrer">GitHub ↗</a>
      </footer>
    </div>
  )
}
