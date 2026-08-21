import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import api from '../api.js'

export default function Deployments() {
  const { id } = useParams()
  const [repo, setRepo] = useState(null)
  const [history, setHistory] = useState([])
  const [version, setVersion] = useState('')
  const [workdir, setWorkdir] = useState('')
  const [healthUrl, setHealthUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  const load = useCallback(async () => {
    const [reposResp, d] = await Promise.all([
      api.get('/repos').catch(() => ({ data: [] })),
      api.get(`/repos/${id}/deployments`),
    ])
    const foundRepo = (reposResp.data || []).find((c) => String(c.id) === String(id))
    setRepo(foundRepo)
    setHistory(d.data || [])
  }, [id])

  useEffect(() => { load() }, [load])

  const deploy = async () => {
    setBusy(true)
    setMessage('')
    try {
      const { data } = await api.post(`/repos/${id}/deploy`, {
        version, workdir, healthUrl,
      })
      setMessage(`Deployment ${data.status}`)
      await load()
    } catch (e) {
      setMessage(`Deploy failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(false)
    }
  }

  const rollback = async () => {
    setBusy(true)
    setMessage('')
    try {
      const { data } = await api.post(`/repos/${id}/rollback`)
      setMessage(data.rolledBack
        ? `Rolled back to ${data.restoredVersion}`
        : `Rollback failed: ${JSON.stringify(data.detail || data)}`)
      await load()
    } catch (e) {
      setMessage(`Rollback failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      {/* Breadcrumbs */}
      <div className="breadcrumbs">
        <Link to="/">Dashboard</Link>
        <span className="breadcrumb-separator">/</span>
        <Link to={`/repos/${id}`}>{repo?.fullName || `Repo #${id}`}</Link>
        <span className="breadcrumb-separator">/</span>
        <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>Deployments</span>
      </div>

      {/* Page Header */}
      <div className="page-header">
        <div>
          <h1 className="page-title">Deployments & Autonomous Rollback</h1>
          <p className="page-subtitle">
            Zero-downtime deployment orchestration and container health checks
          </p>
        </div>

        <div className="row" style={{ gap: '8px' }}>
          <Link className="btn ghost" to={`/repos/${id}`}>
            ← Back to Pipelines
          </Link>
        </div>
      </div>

      {message && (
        <div className="notice-toast">
          <span>{message}</span>
          <button className="btn sm ghost" onClick={() => setMessage('')} style={{ color: 'inherit' }}>✕</button>
        </div>
      )}

      {/* Deploy Card */}
      <section className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
              Trigger Container Deployment
            </h2>
            <p className="card-subtitle">Deploy a new version to the local Docker engine or production target</p>
          </div>
        </div>

        <div className="row" style={{ gap: '12px', alignItems: 'flex-end' }}>
          <div style={{ flex: 1, minWidth: '180px' }}>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '5px' }}>
              Version Tag
            </label>
            <input
              placeholder="v1.0.0 (blank = auto)"
              value={version}
              onChange={(e) => setVersion(e.target.value)}
            />
          </div>

          <div style={{ flex: 2, minWidth: '240px' }}>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '5px' }}>
              Checkout Workdir
            </label>
            <input
              placeholder="e.g. /srv/checkout/myrepo"
              value={workdir}
              onChange={(e) => setWorkdir(e.target.value)}
            />
          </div>

          <div style={{ flex: 2, minWidth: '220px' }}>
            <label style={{ display: 'block', fontSize: '12px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '5px' }}>
              Health Check Endpoint
            </label>
            <input
              placeholder="http://localhost:8080/actuator/health"
              value={healthUrl}
              onChange={(e) => setHealthUrl(e.target.value)}
            />
          </div>

          <button className="btn primary" disabled={busy} onClick={deploy}>
            {busy ? 'Deploying…' : '🚀 Deploy Version'}
          </button>
        </div>
      </section>

      {/* Version History Table */}
      <section className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 14 14"/></svg>
              Deployment History & Versions
            </h2>
            <p className="card-subtitle">Audit log of deployed container versions with instant rollback</p>
          </div>

          <button className="btn danger-ghost sm" disabled={busy || history.length < 2} onClick={rollback}>
            ↩ Rollback to Previous
          </button>
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Version</th>
                <th>Container Image</th>
                <th>Status</th>
                <th>Active Live</th>
                <th>Deployed At</th>
              </tr>
            </thead>
            <tbody>
              {history.map((d) => (
                <tr key={d.id}>
                  <td><strong>{d.version}</strong></td>
                  <td>
                    <span className="branch-tag">{d.image}</span>
                  </td>
                  <td>
                    <span className={`badge ${d.status.toLowerCase()}`}>{d.status}</span>
                  </td>
                  <td>
                    {d.current ? (
                      <span className="badge success">● Current Live</span>
                    ) : (
                      <span className="muted" style={{ fontSize: '12px' }}>Inactive</span>
                    )}
                  </td>
                  <td>{new Date(d.deployedAt).toLocaleString()}</td>
                </tr>
              ))}
              {history.length === 0 && (
                <tr>
                  <td colSpan="5" className="text-center muted" style={{ padding: '36px' }}>
                    No deployments recorded yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
