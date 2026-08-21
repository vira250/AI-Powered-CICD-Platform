import { useCallback, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import api from '../api.js'

export default function Dashboard() {
  const [params] = useSearchParams()
  const [connected, setConnected] = useState([])
  const [installationId, setInstallationId] = useState('')
  const [installations, setInstallations] = useState([])
  const [available, setAvailable] = useState([])
  const [allPipelines, setAllPipelines] = useState([])
  const [searchQuery, setSearchQuery] = useState('')
  const [filterType, setFilterType] = useState('all') // 'all' | 'public' | 'private' | 'generated'
  const [loadingRepos, setLoadingRepos] = useState(false)
  const [busy, setBusy] = useState(null)
  const [message, setMessage] = useState('')
  const [health, setHealth] = useState(null)
  const [user, setUser] = useState(null)

  const loadPipelines = useCallback(async () => {
    try {
      const { data } = await api.get('/pipelines')
      setAllPipelines(data || [])
    } catch (e) {
      console.log('Pipelines list error:', e)
    }
  }, [])

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
    loadPipelines()
    autoDiscover()
    api.get('/health').then(({ data }) => setHealth(data)).catch(() => {})
    api.get('/auth/me').then(({ data }) => { if (data.authenticated) setUser(data) }).catch(() => {})
  }, [loadConnected, loadPipelines, autoDiscover])

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
        owner: repo.owner?.login || repo.owner,
        name: repo.name,
        fullName: repo.full_name || repo.fullName,
        defaultBranch: repo.default_branch || 'main',
        isPrivate: repo.private ?? false,
      })
      await loadConnected()
      setMessage(`Successfully connected ${repo.full_name || repo.fullName}!`)
    } catch (e) {
      setMessage(`Failed to connect repository: ${e.response?.data?.error || e.message}`)
    }
  }

  const removeRepo = async (repo) => {
    const name = repo.fullName || repo.full_name || 'this repository'
    if (!window.confirm(`Are you sure you want to remove "${name}" from connected repositories?`)) {
      return
    }
    const repoId = repo.id || connected.find(c => c.fullName === (repo.full_name || repo.fullName))?.id
    if (!repoId) return

    setBusy(repoId)
    setMessage('')
    try {
      await api.delete(`/repos/${repoId}`)
      setMessage(`Removed "${name}" from connected repositories.`)
      await loadConnected()
      await loadPipelines()
    } catch (e) {
      setMessage(`Failed to remove repository: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
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
      await loadPipelines()
    } catch (e) {
      setMessage(`Failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
    }
  }

  // Set of repo fullNames with generated pipelines
  const generatedRepoNames = new Set(
    allPipelines.map((p) => p.repositoryFullName).filter(Boolean)
  )
  const connectedMap = new Map(connected.map((c) => [c.fullName, c]))

  const filteredAvailable = available.filter((r) => {
    const fullName = r.full_name || r.name || ''
    const matchesSearch =
      fullName.toLowerCase().includes(searchQuery.toLowerCase()) ||
      r.name?.toLowerCase().includes(searchQuery.toLowerCase())
    if (!matchesSearch) return false

    const isPrivate = r.private === true || r.visibility === 'private'
    const isGenerated = generatedRepoNames.has(fullName) || generatedRepoNames.has(r.name)

    if (filterType === 'public') return !isPrivate
    if (filterType === 'private') return isPrivate
    if (filterType === 'generated') return isGenerated
    return true
  })

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
        <div className="row spread" style={{ marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
          <div>
            <h3 style={{ margin: 0 }}>Step 2: Available Repositories</h3>
            {available.length > 0 && (
              <span className="muted" style={{ fontSize: '13px' }}>
                Showing {filteredAvailable.length} of {available.length} repositories
              </span>
            )}
          </div>

          {available.length > 0 && (
            <div className="filter-controls">
              <div className="filter-pills">
                {[
                  { id: 'all', label: 'All' },
                  { id: 'public', label: 'Public' },
                  { id: 'private', label: 'Private' },
                  { id: 'generated', label: 'Generated' },
                ].map((tab) => (
                  <button
                    key={tab.id}
                    type="button"
                    className={`filter-pill ${filterType === tab.id ? 'active' : ''}`}
                    onClick={() => setFilterType(tab.id)}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>
              <input
                type="text"
                placeholder="🔍 Search repository..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                style={{ width: '220px' }}
              />
            </div>
          )}
        </div>

        {loadingRepos && <p className="muted">Fetching available repositories from GitHub App…</p>}

        {available.length > 0 ? (
          <div className="table-responsive">
            <table>
              <thead>
                <tr>
                  <th>Repository</th>
                  <th>Visibility</th>
                  <th>Default Branch</th>
                  <th>Status</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {filteredAvailable.map((r) => {
                  const fullName = r.full_name || r.name
                  const isPrivate = r.private === true || r.visibility === 'private'
                  const isConnected = connectedMap.has(fullName)
                  const isGenerated = generatedRepoNames.has(fullName)
                  const connectedRepo = connectedMap.get(fullName)

                  return (
                    <tr key={r.id}>
                      <td>
                        <strong>{fullName}</strong>
                      </td>
                      <td>
                        <span className={`badge ${isPrivate ? 'private' : 'public'}`}>
                          {isPrivate ? '🔒 Private' : '🌐 Public'}
                        </span>
                      </td>
                      <td><code>{r.default_branch || 'main'}</code></td>
                      <td>
                        <div className="repo-meta">
                          {isConnected && (
                            <span className="badge connected">✓ Connected</span>
                          )}
                          {isGenerated && (
                            <span className="badge generated">⚡ Generated</span>
                          )}
                          {!isConnected && !isGenerated && (
                            <span className="muted" style={{ fontSize: '12px' }}>Not connected</span>
                          )}
                        </div>
                      </td>
                      <td>
                        {isConnected ? (
                          <div className="row" style={{ gap: '8px' }}>
                            <button
                              className="btn primary"
                              disabled={busy === connectedRepo?.id}
                              onClick={() => generatePipeline(connectedRepo)}
                              style={{ padding: '6px 12px', fontSize: '13px' }}
                            >
                              {busy === connectedRepo?.id ? 'Generating…' : '⚡ Generate Pipeline'}
                            </button>
                            <Link
                              className="btn ghost"
                              to={`/repos/${connectedRepo?.id}`}
                              style={{ padding: '6px 12px', fontSize: '13px' }}
                            >
                              View
                            </Link>
                            <button
                              className="btn danger ghost"
                              disabled={busy === connectedRepo?.id}
                              onClick={() => removeRepo(connectedRepo)}
                              style={{ padding: '6px 12px', fontSize: '13px' }}
                              title="Disconnect repository"
                            >
                              🗑️ Remove
                            </button>
                          </div>
                        ) : (
                          <button
                            className="btn primary"
                            onClick={() => connect(r)}
                            style={{ padding: '6px 12px', fontSize: '13px' }}
                          >
                            + Connect Repo
                          </button>
                        )}
                      </td>
                    </tr>
                  )
                })}
                {filteredAvailable.length === 0 && (
                  <tr>
                    <td colSpan="5" className="muted text-center" style={{ padding: '20px' }}>
                      No repositories match "{searchQuery}" with filter "{filterType}"
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        ) : (
          !loadingRepos && (
            <div style={{ padding: '16px 0' }}>
              <p className="muted">No repositories auto-discovered yet. If you recently installed the GitHub App on a new repository, click <strong>🔄 Refresh Repositories</strong> above or enter your Installation ID.</p>
              <div className="row connect-input-row" style={{ marginTop: '12px' }}>
                <input
                  type="number"
                  placeholder="Installation ID (e.g. 154757008)"
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
      <section className="card" id="connected-repos">
        <h3>Connected Repositories</h3>
        {message && <p className="notice">{message}</p>}
        <div className="table-responsive">
          <table>
            <thead>
              <tr>
                <th>Repository</th>
                <th>Visibility</th>
                <th>Connected Date</th>
                <th>Pipeline</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {connected.map((r) => {
                const isGenerated = generatedRepoNames.has(r.fullName)
                return (
                  <tr key={r.id}>
                    <td>
                      <Link to={`/repos/${r.id}`} className="repo-link">
                        <strong>{r.fullName}</strong>
                      </Link>
                    </td>
                    <td>
                      <span className={`badge ${r.isPrivate ? 'private' : 'public'}`}>
                        {r.isPrivate ? '🔒 Private' : '🌐 Public'}
                      </span>
                    </td>
                    <td>{new Date(r.connectedAt).toLocaleString()}</td>
                    <td>
                      {isGenerated ? (
                        <span className="badge generated">⚡ Active</span>
                      ) : (
                        <span className="muted" style={{ fontSize: '13px' }}>None</span>
                      )}
                    </td>
                    <td className="row" style={{ gap: '8px' }}>
                      <button
                        className="btn primary"
                        disabled={busy === r.id}
                        onClick={() => generatePipeline(r)}
                      >
                        {busy === r.id ? 'Generating…' : '⚡ Generate Pipeline'}
                      </button>
                      <Link className="btn ghost" to={`/repos/${r.id}`}>Pipelines</Link>
                      <Link className="btn ghost" to={`/repos/${r.id}/deployments`}>Deployments</Link>
                      <button
                        className="btn danger"
                        disabled={busy === r.id}
                        onClick={() => removeRepo(r)}
                        style={{ padding: '8px 14px', fontSize: '13px' }}
                        title="Remove repository from connected list"
                      >
                        🗑️ Remove
                      </button>
                    </td>
                  </tr>
                )
              })}
              {connected.length === 0 && (
                <tr>
                  <td colSpan="5" className="muted text-center" style={{ padding: '24px' }}>
                    No repositories connected yet. Connect a repository above to begin generating AI CI/CD workflows.
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

