/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Absolute WebSocket URL of the backend, e.g. wss://otter-server.onrender.com/ws. */
  readonly VITE_WS_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
