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

// Friendly default names: an adjective + a river creature, leaning into the otter theme. Picked
// deterministically from the stable userId, so a returning visitor keeps the same name even if the
// name key is cleared, and the same id always renders the same label everywhere.
const ADJECTIVES = [
  'Swift', 'Quiet', 'Amber', 'Clever', 'Teal', 'Brisk', 'Bright', 'Calm',
  'Misty', 'Nimble', 'Mellow', 'Deft', 'Lively', 'Keen', 'Sunny', 'Agile',
]
const ANIMALS = [
  'Otter', 'Heron', 'Marten', 'Beaver', 'Newt', 'Vole', 'Grebe', 'Loon',
  'Mink', 'Tern', 'Dipper', 'Wren', 'Crane', 'Finch', 'Pike', 'Perch',
]

function generatedName(id: string): string {
  let hash = 0
  for (let i = 0; i < id.length; i++) hash = (hash * 31 + id.charCodeAt(i)) | 0
  hash = Math.abs(hash)
  return `${ADJECTIVES[hash % ADJECTIVES.length]} ${ANIMALS[(hash / ADJECTIVES.length | 0) % ANIMALS.length]}`
}

export function displayName(): string {
  const name = read(NAME_KEY)
  if (name && name.trim()) return name
  const fallback = generatedName(userId())
  write(NAME_KEY, fallback)
  return fallback
}

export function setDisplayName(name: string): void {
  const next = name.trim()
  write(NAME_KEY, next || displayName())
}
