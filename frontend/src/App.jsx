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
        <div className="sidebar-header">
          <div className="brand-icon">
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
            </svg>
          </div>
          <div className="brand-info">
            <Link to="/" style={{ textDecoration: 'none' }}>
              <h1 className="brand-title">NexusPipe</h1>
            </Link>
            <span className="brand-badge">AI CI/CD Engine</span>
          </div>
        </div>

        <nav className="sidebar-nav">
          <button
            type="button"
            className={`sidebar-nav-item ${location.pathname === '/' && !location.hash ? 'active' : ''}`}
            onClick={() => navigateTo('/', '')}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <rect x="3" y="3" width="7" height="9" />
              <rect x="14" y="3" width="7" height="5" />
              <rect x="14" y="12" width="7" height="9" />
              <rect x="3" y="16" width="7" height="5" />
            </svg>
            Dashboard
          </button>
          
          <button
            type="button"
            className="sidebar-nav-item"
            onClick={() => navigateTo('/', '#repos')}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20" />
              <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z" />
            </svg>
            Repositories
          </button>
          
          <button
            type="button"
            className="sidebar-nav-item"
            onClick={() => navigateTo('/', '#connected-repos')}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12" />
            </svg>
            Active Pipelines
          </button>
        </nav>

        <div className="sidebar-footer">
          <div className="agent-status-pill">
            <span className="agent-status-indicator">
              <span className="pulse-dot"></span>
              Agent v2.0
            </span>
            <span className="muted" style={{ fontSize: '11px' }}>Online</span>
          </div>

          <button className="btn ghost" onClick={logout} style={{ justifyContent: 'flex-start', width: '100%' }}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
              <polyline points="16 17 21 12 16 7" />
              <line x1="21" y1="12" x2="9" y2="12" />
            </svg>
            Sign Out
          </button>
        </div>
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
