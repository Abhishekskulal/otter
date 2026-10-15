import { useEffect, useState } from 'react'
import { DocumentList } from './DocumentList'
import { Editor } from './Editor'

// A minimal path router: "/" lists documents, "/d/<uuid>" opens one. No router dependency.
function useRoute() {
  const [path, setPath] = useState(window.location.pathname)
  useEffect(() => {
    const onPop = () => setPath(window.location.pathname)
    window.addEventListener('popstate', onPop)
    return () => window.removeEventListener('popstate', onPop)
  }, [])
  const navigate = (to: string) => {
    window.history.pushState({}, '', to)
    setPath(to)
  }
  return { path, navigate }
}

const DOC_PATH = /^\/d\/([0-9a-fA-F-]{36})$/

export function App() {
  const { path, navigate } = useRoute()
  const match = path.match(DOC_PATH)
  if (match) {
    return <Editor documentId={match[1]} onBack={() => navigate('/')} />
  }
  return <DocumentList onOpen={(id) => navigate(`/d/${id}`)} />
}
