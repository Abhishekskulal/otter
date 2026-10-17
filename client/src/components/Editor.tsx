import { useEffect, useRef, useState } from 'react'
import { getDocument, renameDocument } from '../api/documents'
import { ClientDocument, type RemoteParticipant } from '../ot/clientDocument'
import type { OperationSequence } from '../ot/operation'
import { diffToOps } from '../text/diff'
import { type ConnectionStatus, type OtConnection, openOtConnection } from '../ws/otConnection'
import { opSummary } from './opSummary'
import { ThemeToggle } from './theme'

interface Deco {
  readonly anchor: number
  readonly head: number
  readonly colour: string
  readonly label: string
}

interface LogEntry {
  readonly revision: number
  readonly authorId: string
  readonly summary: string
}

const STATES = ['Synchronized', 'AwaitingAck', 'AwaitingWithBuffer'] as const

function initials(name: string): string {
  return name.replace(/^Anon-/, '').slice(0, 2).toUpperCase() || '?'
}

function wsUrl(): string {
  if (import.meta.env.VITE_WS_URL) return import.meta.env.VITE_WS_URL
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

function decorations(doc: ClientDocument): Deco[] {
  const out: Deco[] = []
  for (const [userId, cursor] of doc.remoteCursors) {
    const who = doc.participants.get(userId)
    out.push({ anchor: cursor.anchor, head: cursor.head, colour: who?.colour ?? '#888', label: who?.displayName ?? userId.slice(0, 4) })
  }
  return out
}

export function Editor({ documentId, onBack }: { documentId: string; onBack: () => void }) {
  const [status, setStatus] = useState<ConnectionStatus>('connecting')
  const [content, setContent] = useState('')
  const [stateName, setStateName] = useState('Synchronized')
  const [cursors, setCursors] = useState<Deco[]>([])
  const [participants, setParticipants] = useState<RemoteParticipant[]>([])
  const [log, setLog] = useState<LogEntry[]>([])
  const [title, setTitle] = useState('Untitled')
  const [canUndo, setCanUndo] = useState(false)
  const [canRedo, setCanRedo] = useState(false)
  const [waking, setWaking] = useState(false)
  const docRef = useRef<ClientDocument | null>(null)
  const areaRef = useRef<HTMLTextAreaElement | null>(null)
  const mirrorRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    getDocument(documentId).then((d) => setTitle(d.title)).catch(() => setTitle('Untitled'))
  }, [documentId])

  // Only surface the "waking" banner for a genuinely slow connect (a cold backend), not the
  // sub-second reconnect that happens every time a document opens on a warm backend.
  useEffect(() => {
    if (status === 'open') {
      setWaking(false)
      return
    }
    const timer = setTimeout(() => setWaking(true), 2500)
    return () => clearTimeout(timer)
  }, [status])

  useEffect(() => {
    let conn: OtConnection | null = null
    const doc = new ClientDocument({
      send: (ops, base, id) => conn?.sendOp(ops, base, id),
      sendCursor: (a, h, base) => conn?.sendCursor(a, h, base),
      onChange: () => {
        setContent(doc.content)
        setStateName(doc.stateName())
        setCursors(decorations(doc))
        setParticipants([...doc.participants.values()])
        setCanUndo(doc.canUndo())
        setCanRedo(doc.canRedo())
      },
      newClientOpId: () => crypto.randomUUID(),
    })
    docRef.current = doc

    const record = (ops: OperationSequence, revision: number, authorId: string) =>
      setLog((prev) => [{ revision, authorId, summary: opSummary(ops) }, ...prev].slice(0, 60))

    conn = openOtConnection(wsUrl(), documentId, {
      onStatus: setStatus,
      onSync: (text, revision, members) => doc.onSync(text, revision, members),
      onOp: (ops, revision, clientOpId, authorId) => {
        doc.onServerOp(ops, revision, clientOpId)
        record(ops, revision, authorId)
      },
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
  }, [documentId])

  const sendCaret = () => {
    const doc = docRef.current
    const area = areaRef.current
    if (doc && area) doc.localCursor(area.selectionStart, area.selectionEnd)
  }

  const onEdit = (event: React.ChangeEvent<HTMLTextAreaElement>) => {
    const doc = docRef.current
    if (!doc) return
    for (const op of diffToOps(doc.content, event.target.value)) doc.localEdit(op)
    sendCaret()
  }

  // Route Ctrl/Cmd+Z and Shift+Z (or Ctrl+Y) to OT undo/redo, suppressing the textarea's native undo.
  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    const mod = event.ctrlKey || event.metaKey
    const key = event.key.toLowerCase()
    if (mod && key === 'z') {
      event.preventDefault()
      if (event.shiftKey) docRef.current?.redo()
      else docRef.current?.undo()
    } else if (mod && key === 'y') {
      event.preventDefault()
      docRef.current?.redo()
    }
  }

  const commitTitle = () => {
    const next = title.trim() || 'Untitled'
    setTitle(next)
    renameDocument(documentId, next).catch(() => {})
  }

  const syncScroll = () => {
    if (areaRef.current && mirrorRef.current) {
      mirrorRef.current.scrollTop = areaRef.current.scrollTop
      mirrorRef.current.scrollLeft = areaRef.current.scrollLeft
    }
  }

  const byId = new Map(participants.map((p) => [p.userId, p]))

  return (
    <div className="wrap">
      <div className="bar">
        <button className="linkbtn" onClick={onBack} type="button">← All documents</button>
        <span className="spacer"></span>
        {participants.length > 0 && (
          <div className="avatars">
            {participants.map((p) => (
              <span key={p.userId} className="av" style={{ background: p.colour }} title={p.displayName}>{initials(p.displayName)}</span>
            ))}
          </div>
        )}
        <span className={`pill ${status === 'open' ? 'live' : 'waking'}`}>
          <span className="dot"></span>{status === 'open' ? 'Live' : 'Connecting'}
        </span>
        <button className="iconbtn" onClick={() => docRef.current?.undo()} disabled={!canUndo} type="button" title="Undo (Ctrl+Z)" aria-label="Undo">↶</button>
        <button className="iconbtn" onClick={() => docRef.current?.redo()} disabled={!canRedo} type="button" title="Redo (Ctrl+Shift+Z)" aria-label="Redo">↷</button>
        <ThemeToggle />
      </div>

      <input
        className="title-input"
        value={title}
        onChange={(e) => setTitle(e.target.value)}
        onBlur={commitTitle}
        onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
        aria-label="Document title"
      />

      {waking && (
        <div className="banner">
          <span className="b-dot"></span>
          <span>Waking the free-tier backend — this can take 30–60 seconds and reconnects on its own.</span>
        </div>
      )}

      <div className="workspace">
        <section className="panel editor">
          <div className="phead"><span className="fname">{title || 'untitled'}</span></div>
          <div className="ed-stack">
            <div className="ed-shared ed-mirror" ref={mirrorRef} aria-hidden>
              <Decorated content={content} cursors={cursors} />
            </div>
            <textarea
              className="ed-shared ed-area"
              ref={areaRef}
              value={content}
              onChange={onEdit}
              onKeyDown={onKeyDown}
              onSelect={sendCaret}
              onKeyUp={sendCaret}
              onClick={sendCaret}
              onScroll={syncScroll}
              spellCheck={false}
            />
          </div>
        </section>

        <aside className="insp">
          <div className="panel">
            <div className="phead">Client state</div>
            <div className="states">
              {STATES.map((s) => (
                <div key={s} className={`state ${s === stateName ? 'on' : ''}`}><span className="led"></span>{s}</div>
              ))}
              <div className="note">at most one op in flight</div>
            </div>
          </div>
          <div className="panel">
            <div className="phead">Revision log <span style={{ color: 'var(--faint)' }}>append-only</span></div>
            <div className="log">
              {log.length === 0 && <div className="empty">No operations yet.</div>}
              {log.map((e, i) => {
                const who = byId.get(e.authorId)
                return (
                  <div className="op" key={`${e.revision}-${i}`}>
                    <span className="rev">{e.revision}</span>
                    <span className="by"><span className="d" style={{ background: who?.colour ?? 'var(--faint)' }}></span>{who?.displayName ?? '—'}</span>
                    <span className="act">{e.summary}</span>
                  </div>
                )
              })}
            </div>
          </div>
        </aside>
      </div>
    </div>
  )
}

function Decorated({ content, cursors }: { content: string; cursors: Deco[] }) {
  const len = content.length
  const clamp = (n: number) => Math.max(0, Math.min(n, len))
  const bounds = new Set<number>([0, len])
  for (const c of cursors) {
    bounds.add(clamp(c.head))
    if (c.anchor !== c.head) {
      bounds.add(clamp(Math.min(c.anchor, c.head)))
      bounds.add(clamp(Math.max(c.anchor, c.head)))
    }
  }
  const marks = [...bounds].sort((a, b) => a - b)
  const nodes: React.ReactNode[] = []
  let key = 0
  const caretsAt = (offset: number) =>
    cursors
      .filter((c) => clamp(c.head) === offset)
      .map((c) => (
        <span className="caret" key={key++} style={{ borderLeftColor: c.colour }}>
          <span className="flag" style={{ background: c.colour }}>{c.label}</span>
        </span>
      ))

  nodes.push(...caretsAt(0))
  for (let i = 0; i < marks.length - 1; i++) {
    const a = marks[i]
    const b = marks[i + 1]
    const seg = content.slice(a, b)
    const sel = cursors.find(
      (c) => c.anchor !== c.head && clamp(Math.min(c.anchor, c.head)) <= a && clamp(Math.max(c.anchor, c.head)) >= b,
    )
    nodes.push(
      sel ? (
        <span className="sel" key={key++} style={{ background: `color-mix(in srgb, ${sel.colour} 22%, transparent)` }}>{seg}</span>
      ) : (
        <span key={key++}>{seg}</span>
      ),
    )
    nodes.push(...caretsAt(b))
  }
  return <>{nodes}</>
}
