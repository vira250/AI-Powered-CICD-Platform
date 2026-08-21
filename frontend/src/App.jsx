import { Navigate, Route, Routes, Link, useNavigate, useLocation } from 'react-router-dom'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import RepoDetail from './pages/RepoDetail.jsx'
import Deployments from './pages/Deployments.jsx'

function Layout({ children }) {
  const navigate = useNavigate()
  const location = useLocation()

  const logout = () => {
    localStorage.removeItem('session_token')
    navigate('/login')
  }

  const navigateTo = (path, hash) => {
    if (location.pathname === path) {
      if (hash) {
        const el = document.getElementById(hash.replace('#', ''))
        if (el) el.scrollIntoView({ behavior: 'smooth' })
      } else {
        window.scrollTo({ top: 0, behavior: 'smooth' })
      }
    } else {
      navigate(path + (hash || ''))
      if (hash) {
        setTimeout(() => {
          const el = document.getElementById(hash.replace('#', ''))
          if (el) el.scrollIntoView({ behavior: 'smooth' })
        }, 150)
      }
    }
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <div>
          <Link to="/" style={{ textDecoration: 'none' }}>
            <h1 className="brand">AI CI/CD</h1>
          </Link>
          <span className="badge" style={{ fontSize: '11px', marginTop: '4px' }}>
            NexusPipe Multi-Agent
          </span>
        </div>

        <nav>
          <button
            type="button"
            className={`nav-item ${location.pathname === '/' ? 'active' : ''}`}
            onClick={() => navigateTo('/', '')}
          >
            📊 Dashboard
          </button>
          <button
            type="button"
            className="nav-item"
            onClick={() => navigateTo('/', '#repos')}
          >
            📦 Repositories
          </button>
          <button
            type="button"
            className="nav-item"
            onClick={() => navigateTo('/', '#connected-repos')}
          >
            ⚡ Pipelines
          </button>
        </nav>

        <button className="btn ghost" onClick={logout} style={{ marginTop: 'auto', justifyContent: 'flex-start' }}>
          🚪 Log out
        </button>
      </aside>
      <main className="content">{children}</main>
    </div>
  )
}

function RequireAuth({ children }) {
  const token = localStorage.getItem('session_token')
  return token ? <Layout>{children}</Layout> : <Navigate to="/login" replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/" element={<RequireAuth><Dashboard /></RequireAuth>} />
      <Route path="/repos/:id" element={<RequireAuth><RepoDetail /></RequireAuth>} />
      <Route path="/repos/:id/deployments" element={<RequireAuth><Deployments /></RequireAuth>} />
    </Routes>
  )
}
