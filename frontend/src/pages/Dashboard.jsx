import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import api from '../api.js'

export default function Dashboard() {
  const [params] = useSearchParams()
  const [connected, setConnected] = useState([])
  const [installationId, setInstallationId] = useState('')
  const [installations, setInstallations] = useState([])
  const [available, setAvailable] = useState([])
  const [loadingRepos, setLoadingRepos] = useState(false)
  const [busy, setBusy] = useState(null)
  const [message, setMessage] = useState('')
  const [health, setHealth] = useState(null)
  const [user, setUser] = useState(null)

  const loadConnected = useCallback(async () => {
    try {
      const { data } = await api.get('/repos')
      setConnected(data)
    } catch (e) {
      console.error(e)
    }
  }, [])

  // Auto-discover installations and available repos from GitHub App
  const autoDiscover = useCallback(async () => {
    setLoadingRepos(true)
    try {
      // 1. Fetch available repos directly across all installations
      const { data: repos } = await api.get('/repos/auto-available')
      if (repos && repos.length > 0) {
        setAvailable(repos)
        if (repos[0].installationId) {
          setInstallationId(String(repos[0].installationId))
        }
      }

      // 2. Fetch installations
      const { data: insts } = await api.get('/repos/installations')
      if (insts && insts.length > 0) {
        setInstallations(insts)
      }
    } catch (e) {
      console.log('Auto discovery error:', e)
    } finally {
      setLoadingRepos(false)
    }
  }, [])

  useEffect(() => {
    loadConnected()
    autoDiscover()
    api.get('/health').then(({ data }) => setHealth(data)).catch(() => {})
    api.get('/auth/me').then(({ data }) => { if (data.authenticated) setUser(data) }).catch(() => {})
  }, [loadConnected, autoDiscover])

  // If URL contains installation_id parameter after installing app
  useEffect(() => {
    const instId = params.get('installation_id') || params.get('installationId')
    if (instId) {
      setInstallationId(instId)
      api.get('/repos/available', { params: { installationId: instId } })
        .then(({ data }) => setAvailable(data))
        .catch(() => {})
    }
  }, [params])

  const loadAvailable = async () => {
    if (!installationId) return
    setLoadingRepos(true)
    try {
      const { data } = await api.get('/repos/available', { params: { installationId } })
      setAvailable(data)
    } catch (e) {
      setMessage(`Error loading repos for installation ID ${installationId}: ${e.response?.data?.error || e.message}`)
    } finally {
      setLoadingRepos(false)
    }
  }

  const connect = async (repo) => {
    const effectiveInstId = repo.installationId || installationId
    try {
      await api.post('/repos/connect', {
        installationId: Number(effectiveInstId),
        owner: repo.owner.login,
        name: repo.name,
        fullName: repo.full_name,
        defaultBranch: repo.default_branch || 'main',
      })
      await loadConnected()
      setMessage(`Successfully connected ${repo.full_name}!`)
    } catch (e) {
      setMessage(`Failed to connect repository: ${e.response?.data?.error || e.message}`)
    }
  }

  const generatePipeline = async (repo) => {
    setBusy(repo.id)
    setMessage('')
    try {
      const { data } = await api.post(`/repos/${repo.id}/generate-pipeline`)
      setMessage(
        `Pipeline ${data.status} for ${repo.fullName} ` +
        `(stack: ${data.stack?.language}/${data.stack?.build_tool}, template: ${data.templateUsed})`,
      )
    } catch (e) {
      setMessage(`Failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
    }
  }

  const githubInstallUrl = "https://github.com/apps/nexus-pipe/installations/new"

  return (
    <div className="dashboard-container">
      <div className="dashboard-header">
        <div>
          <h2>Dashboard</h2>
          {user && <p className="welcome-text">Welcome back, <strong>{user.name || user.email || user.login}</strong></p>}
        </div>
        {health && (
          <div className="status-chip">
            <span className={`status-dot ${health.agents?.status === 'ok' ? 'online' : 'offline'}`} />
            Agents: {health.agents?.status === 'ok' ? 'Online' : 'Offline'}
            {health.agents?.llm_model ? ` (${health.agents.llm_model})` : ''}
          </div>
        )}
      </div>

      {/* GitHub App Installation Hero Section */}
      <section className="card hero-card">
        <div className="hero-content">
          <div className="hero-badge">Step 1</div>
          <h3>Install GitHub App</h3>
          <p>
            Authorize our GitHub App (<code>nexus-pipe</code>) to connect your repositories. Our backend will automatically detect your installations.
          </p>

          <div className="action-row" style={{ marginTop: '16px' }}>
            <a
              href={githubInstallUrl}
              target="_blank"
              rel="noreferrer"
              className="btn primary large github-install-btn"
            >
              <svg height="18" width="18" viewBox="0 0 16 16" fill="currentColor" style={{ marginRight: 8 }}>
                <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/>
              </svg>
              Install GitHub App on GitHub ↗
            </a>
            <button className="btn ghost" onClick={autoDiscover} style={{ marginLeft: '12px' }}>
              🔄 Refresh Repositories
            </button>
          </div>
        </div>
      </section>

      {/* Discovered & Available Repositories */}
      <section className="card" id="repos">
        <div className="row spread">
          <h3>Step 2: Available Repositories</h3>
          {installations.length > 0 && (
            <span className="muted" style={{ fontSize: '13px' }}>
              Found {installations.length} active installation(s)
            </span>
          )}
        </div>

        {loadingRepos && <p className="muted">Fetching available repositories from GitHub App…</p>}

        {available.length > 0 ? (
          <div className="table-responsive">
            <table>
              <thead><tr><th>Repository</th><th>Default Branch</th><th>Action</th></tr></thead>
              <tbody>
                {available.map((r) => (
                  <tr key={r.id}>
                    <td><strong>{r.full_name}</strong></td>
                    <td><code>{r.default_branch || 'main'}</code></td>
                    <td>
                      <button className="btn primary" onClick={() => connect(r)}>
                        + Connect Repo
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          !loadingRepos && (
            <div style={{ padding: '16px 0' }}>
              <p className="muted">No repositories auto-discovered yet. If you have an Installation ID, enter it below or install the GitHub App above.</p>
              <div className="row connect-input-row" style={{ marginTop: '12px' }}>
                <input
                  type="number"
                  placeholder="Installation ID (e.g. 6948271)"
                  value={installationId}
                  onChange={(e) => setInstallationId(e.target.value)}
                  className="installation-input"
                />
                <button className="btn primary" onClick={loadAvailable} disabled={!installationId}>
                  List Repositories
                </button>
              </div>
            </div>
          )
        )}
      </section>

      {/* Connected Repositories */}
      <section className="card">
        <h3>Connected Repositories</h3>
        {message && <p className="notice">{message}</p>}
        <div className="table-responsive">
          <table>
            <thead><tr><th>Repository</th><th>Connected Date</th><th>Actions</th></tr></thead>
            <tbody>
              {connected.map((r) => (
                <tr key={r.id}>
                  <td><Link to={`/repos/${r.id}`} className="repo-link"><strong>{r.fullName}</strong></Link></td>
                  <td>{new Date(r.connectedAt).toLocaleString()}</td>
                  <td className="row">
                    <button
                      className="btn primary"
                      disabled={busy === r.id}
                      onClick={() => generatePipeline(r)}
                    >
                      {busy === r.id ? 'Generating…' : '⚡ Generate Pipeline'}
                    </button>
                    <Link className="btn ghost" to={`/repos/${r.id}`}>Pipelines</Link>
                    <Link className="btn ghost" to={`/repos/${r.id}/deployments`}>Deployments</Link>
                  </td>
                </tr>
              ))}
              {connected.length === 0 && (
                <tr><td colSpan="3" className="muted text-center" style={{ padding: '24px' }}>No repositories connected yet. Connect a repository above to begin generating AI CI/CD workflows.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
