import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import api from '../api.js'

export default function Deployments() {
  const { id } = useParams()
  const [history, setHistory] = useState([])
  const [version, setVersion] = useState('')
  const [workdir, setWorkdir] = useState('')
  const [healthUrl, setHealthUrl] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

  const load = useCallback(async () => {
    const { data } = await api.get(`/repos/${id}/deployments`)
    setHistory(data)
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
      <h2 id="deploy">Deployment & Rollback</h2>
      {message && <p className="notice">{message}</p>}

      <section className="card">
        <h3>Deploy new version</h3>
        <div className="row">
          <input placeholder="Version (e.g. v1.3.0 — blank = auto)"
                 value={version} onChange={(e) => setVersion(e.target.value)} />
          <input placeholder="Checkout path with Dockerfile (e.g. /srv/checkout/myrepo)"
                 value={workdir} onChange={(e) => setWorkdir(e.target.value)} />
          <input placeholder="Health endpoint (optional)"
                 value={healthUrl} onChange={(e) => setHealthUrl(e.target.value)} />
          <button className="btn primary" disabled={busy} onClick={deploy}>
            {busy ? 'Working…' : 'Deploy'}
          </button>
        </div>
        <p className="muted">
          The Deployment Agent builds the Docker image, pushes it to the registry,
          deploys it, runs the health check and auto-rolls back on failure.
        </p>
      </section>

      <section className="card">
        <div className="row spread">
          <h3>Version history</h3>
          <button className="btn danger" disabled={busy} onClick={rollback}>
            Roll back to previous version
          </button>
        </div>
        <table>
          <thead><tr><th>Version</th><th>Image</th><th>Status</th><th>Current</th><th>Deployed at</th></tr></thead>
          <tbody>
            {history.map((d) => (
              <tr key={d.id}>
                <td>{d.version}</td>
                <td><code>{d.image}</code></td>
                <td><span className={`badge ${d.status.toLowerCase()}`}>{d.status}</span></td>
                <td>{d.current ? 'current' : ''}</td>
                <td>{new Date(d.deployedAt).toLocaleString()}</td>
              </tr>
            ))}
            {history.length === 0 && (
              <tr><td colSpan="5" className="muted">No deployments recorded yet.</td></tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  )
}
