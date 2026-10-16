// A stable pseudonymous identity kept in this browser. The server trusts it and persists the
// user record, so a refresh or reconnect keeps the same name, colour, and document ownership.

const ID_KEY = 'otter-user-id'
const NAME_KEY = 'otter-user-name'

function read(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    /* storage unavailable */
  }
}

export function userId(): string {
  let id = read(ID_KEY)
  if (!id) {
    id = crypto.randomUUID()
    write(ID_KEY, id)
  }
  return id
}

export function displayName(): string {
  const name = read(NAME_KEY)
  if (name && name.trim()) return name
  const fallback = 'Anon-' + userId().slice(0, 4)
  write(NAME_KEY, fallback)
  return fallback
}

export function setDisplayName(name: string): void {
  const next = name.trim()
  write(NAME_KEY, next || displayName())
}
