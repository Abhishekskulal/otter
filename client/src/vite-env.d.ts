/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Absolute WebSocket URL of the backend, e.g. wss://otter-server.onrender.com/ws. */
  readonly VITE_WS_URL?: string
  /** Base URL of the backend REST API, e.g. https://otter-server.onrender.com. Empty = same origin. */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
