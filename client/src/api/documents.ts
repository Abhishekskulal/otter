import { userId } from '../identity'

export interface DocumentSummary {
  readonly id: string
  readonly title: string
  readonly revision: number
  readonly updatedAt: string
}

// Same origin (Vite proxy / single-origin deploy) unless a split deploy points at the backend.
const base = import.meta.env.VITE_API_URL ?? ''

async function json<T>(response: Response): Promise<T> {
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export function listDocuments(): Promise<DocumentSummary[]> {
  return fetch(`${base}/api/documents`).then(json<DocumentSummary[]>)
}

export function getDocument(id: string): Promise<DocumentSummary> {
  return fetch(`${base}/api/documents/${id}`).then(json<DocumentSummary>)
}

export function createDocument(title?: string): Promise<DocumentSummary> {
  return fetch(`${base}/api/documents`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title: title ?? '', ownerId: userId() }),
  }).then(json<DocumentSummary>)
}

export function renameDocument(id: string, title: string): Promise<DocumentSummary> {
  return fetch(`${base}/api/documents/${id}`, {
    method: 'PATCH',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ title }),
  }).then(json<DocumentSummary>)
}
