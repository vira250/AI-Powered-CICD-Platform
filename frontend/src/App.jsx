import { Navigate, Route, Routes, Link, useNavigate } from 'react-router-dom'
import Login from './pages/Login.jsx'
import Dashboard from './pages/Dashboard.jsx'
import RepoDetail from './pages/RepoDetail.jsx'
import Deployments from './pages/Deployments.jsx'

function Layout({ children }) {
  const navigate = useNavigate()
  const logout = () => {
    localStorage.removeItem('session_token')
    navigate('/login')
  }
  return (
    <div className="layout">
      <aside className="sidebar">
        <h1 className="brand">AI CI/CD</h1>
        <nav>
          <Link to="/">Dashboard</Link>
          <a href="#repos">Repositories</a>
          <a href="#pipelines">Pipeline Monitoring</a>
          <a href="#logs">Logs & AI Analysis</a>
          <a href="#deploy">Deployment & Rollback</a>
        </nav>
        <button className="btn ghost" onClick={logout}>Log out</button>
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
