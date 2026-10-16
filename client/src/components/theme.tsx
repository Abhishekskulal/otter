import { useEffect, useState } from 'react'

type Mode = 'system' | 'light' | 'dark'

const ICON: Record<Mode, string> = { system: '◐', light: '☀', dark: '☾' }

function readMode(): Mode {
  try {
    const stored = localStorage.getItem('otter-theme')
    if (stored === 'light' || stored === 'dark' || stored === 'system') return stored
  } catch {
    /* storage unavailable */
  }
  return 'system'
}

export function ThemeToggle() {
  const [mode, setMode] = useState<Mode>(readMode)

  useEffect(() => {
    const root = document.documentElement
    if (mode === 'system') root.removeAttribute('data-theme')
    else root.setAttribute('data-theme', mode)
    try {
      localStorage.setItem('otter-theme', mode)
    } catch {
      /* ignore */
    }
  }, [mode])

  const cycle = () => setMode((m) => (m === 'system' ? 'light' : m === 'light' ? 'dark' : 'system'))

  return (
    <button className="iconbtn" onClick={cycle} type="button" aria-label="Change theme">
      <span aria-hidden>{ICON[mode]}</span>
      <span>{mode[0].toUpperCase() + mode.slice(1)}</span>
    </button>
  )
}
