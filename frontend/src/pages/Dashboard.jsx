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
  const [filterType, setFilterType] = useState('all') // 'all' | 'connected' | 'generated' | 'public' | 'private'
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
      const { data: repos } = await api.get('/repos/auto-available')
      if (repos && repos.length > 0) {
        setAvailable(repos)
        if (repos[0].installationId) {
          setInstallationId(String(repos[0].installationId))
        }
      }

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
      const costStr = data.creditsUsed != null ? `$${Number(data.creditsUsed).toFixed(5)}` : 'Free'
      const tokensStr = data.totalTokens ? ` · ${data.totalTokens} tokens` : ''
      setMessage(
        `🎉 Pipeline #${data.pipelineId} ${data.status} for ${repo.fullName} (${data.templateUsed}) — Cost: ${costStr}${tokensStr}`,
      )
      await loadPipelines()
    } catch (e) {
      setMessage(`Failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
    }
  }

  const connectedMap = new Map(connected.map((c) => [c.fullName, c]))
  const generatedRepoNames = new Set(
    allPipelines
      .filter((p) => p.status === 'PUSHED' || p.status === 'GENERATED' || p.status === 'SUCCESS')
      .map((p) => p.repositoryFullName)
  )

  const totalCost = allPipelines.reduce((sum, p) => sum + (p.creditsUsed || 0), 0)
  const totalTokens = allPipelines.reduce((sum, p) => sum + (p.totalTokens || 0), 0)

  const filteredAvailable = available.filter((r) => {
    const fullName = r.full_name || r.fullName || ''
    const matchesSearch = fullName.toLowerCase().includes(searchQuery.toLowerCase())
    if (!matchesSearch) return false

    const isConnected = connectedMap.has(fullName)
    const isGenerated = generatedRepoNames.has(fullName)
    const isPrivate = r.private ?? r.isPrivate ?? false

    if (filterType === 'connected') return isConnected
    if (filterType === 'generated') return isGenerated
    if (filterType === 'public') return !isPrivate
    if (filterType === 'private') return isPrivate
    return true
  })

  return (
    <div>
      {/* Page Header */}
      <div className="page-header">
        <div>
          <h1 className="page-title">Autonomous CI/CD Platform</h1>
          <p className="page-subtitle">
            {user?.name ? `Welcome back, ${user.name}` : 'AI Multi-Agent DevOps Orchestration for GitHub Actions'}
          </p>
        </div>

        <div className="row" style={{ gap: '10px' }}>
          <div className="agent-status-pill">
            <span className="agent-status-indicator">
              <span className="pulse-dot"></span>
              {health?.status === 'ok' ? 'Agents Healthy' : 'Agents Active'}
            </span>
            <span className="muted" style={{ fontSize: '11px', marginLeft: '6px' }}>
              {health?.llm_model || 'gemini-3.6-flash'}
            </span>
          </div>
        </div>
      </div>

      {/* Metric Cards Grid */}
      <div className="metrics-grid">
        <div className="metric-card">
          <div className="metric-label">
            Connected Repositories
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
          </div>
          <div className="metric-value">{connected.length}</div>
          <div className="metric-meta">
            <span className="badge success">{connected.length > 0 ? 'Active' : 'Idle'}</span>
            <span>{available.length} available</span>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">
            Pipelines Synthesized
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
          </div>
          <div className="metric-value">{allPipelines.length}</div>
          <div className="metric-meta">
            <span className="badge generated">{generatedRepoNames.size} repositories</span>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">
            Self-Healing & Remediation
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
          </div>
          <div className="metric-value" style={{ color: '#10b981' }}>Active</div>
          <div className="metric-meta">
            <span>Automated log diagnostics loop</span>
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-label">
            LLM Generation Cost
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/></svg>
          </div>
          <div className="metric-value" style={{ color: '#10b981' }}>
            ${totalCost.toFixed(5)}
          </div>
          <div className="metric-meta">
            <span className="muted">{totalTokens.toLocaleString()} tokens tracked</span>
          </div>
        </div>
      </div>

      {message && (
        <div className="notice-toast">
          <span>{message}</span>
          <button className="btn sm ghost" onClick={() => setMessage('')} style={{ color: 'inherit' }}>✕</button>
        </div>
      )}

      {/* GitHub App Setup Hero Card */}
      <section className="setup-hero">
        <div className="row spread" style={{ alignItems: 'flex-start' }}>
          <div>
            <h3>🚀 Step 1: Connect your GitHub Account</h3>
            <p className="muted" style={{ margin: 0, maxWidth: '620px' }}>
              Install the GitHub App on your organization or repositories to give the AI Agents permission to generate and push CI/CD workflows automatically.
            </p>
          </div>

          <a
            className="btn github lg"
            href="https://github.com/apps/aicicdplatform-2026/installations/new"
            target="_blank"
            rel="noreferrer"
          >
            <svg height="18" width="18" viewBox="0 0 16 16" fill="currentColor">
              <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/>
            </svg>
            Install GitHub App
          </a>
        </div>

        <div className="setup-steps-row">
          <div className="setup-step-box">
            <div className="setup-step-num">1</div>
            <p><strong>Authorize App</strong><br />Grant access to your repository list</p>
          </div>
          <div className="setup-step-box">
            <div className="setup-step-num">2</div>
            <p><strong>Auto-Scan Repo</strong><br />Context ingestion extracts build files</p>
          </div>
          <div className="setup-step-box">
            <div className="setup-step-num">3</div>
            <p><strong>Synthesize Pipeline</strong><br />LLM writes & commits GitHub Actions YAML</p>
          </div>
        </div>

        <div className="row" style={{ gap: '10px', marginTop: '12px' }}>
          <div style={{ flex: 1, maxWidth: '320px' }}>
            <input
              type="text"
              placeholder="Installation ID (e.g. 110978393)"
              value={installationId}
              onChange={(e) => setInstallationId(e.target.value)}
            />
          </div>
          <button className="btn" onClick={loadAvailable} disabled={!installationId || loadingRepos}>
            {loadingRepos ? 'Scanning…' : '🔄 Refresh Repositories'}
          </button>
        </div>
      </section>

      {/* Available Repositories Panel */}
      <section className="card" id="repos">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
              Available Repositories
            </h2>
            <p className="card-subtitle">Repositories available through your GitHub App installations</p>
          </div>

          <button className="btn sm" onClick={autoDiscover} disabled={loadingRepos}>
            {loadingRepos ? 'Discovering…' : '⚡ Auto-Discover'}
          </button>
        </div>

        {/* Search & Filter Toolbar */}
        <div className="filter-toolbar">
          <div className="search-input-wrapper">
            <span className="search-icon">🔍</span>
            <input
              type="text"
              placeholder="Search repositories by name…"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
            />
          </div>

          <div className="segmented-control">
            <button
              className={`segmented-btn ${filterType === 'all' ? 'active' : ''}`}
              onClick={() => setFilterType('all')}
            >
              All ({available.length})
            </button>
            <button
              className={`segmented-btn ${filterType === 'connected' ? 'active' : ''}`}
              onClick={() => setFilterType('connected')}
            >
              Connected ({connected.length})
            </button>
            <button
              className={`segmented-btn ${filterType === 'generated' ? 'active' : ''}`}
              onClick={() => setFilterType('generated')}
            >
              Active CI/CD ({generatedRepoNames.size})
            </button>
            <button
              className={`segmented-btn ${filterType === 'public' ? 'active' : ''}`}
              onClick={() => setFilterType('public')}
            >
              Public
            </button>
            <button
              className={`segmented-btn ${filterType === 'private' ? 'active' : ''}`}
              onClick={() => setFilterType('private')}
            >
              Private
            </button>
          </div>
        </div>

        {/* Repositories Table */}
        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Repository</th>
                <th>Visibility</th>
                <th>Branch</th>
                <th>Pipeline Status</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filteredAvailable.map((r) => {
                const fullName = r.full_name || r.fullName
                const isPrivate = r.private ?? r.isPrivate ?? false
                const isConnected = connectedMap.has(fullName)
                const isGenerated = generatedRepoNames.has(fullName)
                const connectedRepo = connectedMap.get(fullName)

                return (
                  <tr key={r.id || fullName}>
                    <td>
                      <div className="row" style={{ gap: '8px' }}>
                        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" style={{ color: 'var(--text-tertiary)' }}><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
                        <strong style={{ color: 'var(--text-primary)' }}>{fullName}</strong>
                      </div>
                    </td>
                    <td>
                      <span className={`badge ${isPrivate ? 'private' : 'public'}`}>
                        {isPrivate ? '🔒 Private' : '🌐 Public'}
                      </span>
                    </td>
                    <td>
                      <span className="branch-tag">
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="6" y1="3" x2="6" y2="15"/><circle cx="18" cy="6" r="3"/><circle cx="6" cy="18" r="3"/><path d="M18 9a9 9 0 0 1-9 9"/></svg>
                        {r.default_branch || r.defaultBranch || 'main'}
                      </span>
                    </td>
                    <td>
                      {isGenerated ? (
                        <span className="badge generated">⚡ Workflow Active</span>
                      ) : isConnected ? (
                        <span className="badge success">✓ Connected</span>
                      ) : (
                        <span className="badge muted-badge">Not Connected</span>
                      )}
                    </td>
                    <td className="text-right">
                      {isConnected ? (
                        <div className="row" style={{ justifyContent: 'flex-end', gap: '6px' }}>
                          <button
                            className="btn primary sm"
                            disabled={busy === connectedRepo?.id}
                            onClick={() => generatePipeline(connectedRepo)}
                          >
                            {busy === connectedRepo?.id ? '⚡ Generating…' : '⚡ Generate Pipeline'}
                          </button>
                          <Link className="btn ghost sm" to={`/repos/${connectedRepo?.id}`}>
                            View Details
                          </Link>
                          <button
                            className="btn danger-ghost sm"
                            disabled={busy === connectedRepo?.id}
                            onClick={() => removeRepo(connectedRepo)}
                            title="Disconnect repository"
                          >
                            🗑️
                          </button>
                        </div>
                      ) : (
                        <button
                          className="btn primary sm"
                          onClick={() => connect(r)}
                        >
                          + Connect Repo
                        </button>
                      )}
                    </td>
                  </tr>
                )
              })}

              {filteredAvailable.length === 0 && !loadingRepos && (
                <tr>
                  <td colSpan="5" className="text-center muted" style={{ padding: '36px' }}>
                    {available.length === 0
                      ? 'No repositories found. Enter your Installation ID above or click "Install GitHub App".'
                      : `No repositories match "${searchQuery}"`}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {/* Connected Repositories Section */}
      <section className="card" id="connected-repos">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
              Connected Repositories & Workflows
            </h2>
            <p className="card-subtitle">Active repositories ready for AI pipeline orchestration</p>
          </div>
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Repository</th>
                <th>Visibility</th>
                <th>Connected Date</th>
                <th>Workflow Status</th>
                <th className="text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {connected.map((r) => {
                const isGenerated = generatedRepoNames.has(r.fullName)
                return (
                  <tr key={r.id}>
                    <td>
                      <Link to={`/repos/${r.id}`} style={{ color: 'var(--text-primary)', textDecoration: 'none', fontWeight: 600 }}>
                        {r.fullName}
                      </Link>
                    </td>
                    <td>
                      <span className={`badge ${r.isPrivate ? 'private' : 'public'}`}>
                        {r.isPrivate ? '🔒 Private' : '🌐 Public'}
                      </span>
                    </td>
                    <td>{new Date(r.connectedAt).toLocaleDateString()}</td>
                    <td>
                      {isGenerated ? (
                        <span className="badge generated">⚡ Workflow Active</span>
                      ) : (
                        <span className="badge muted-badge">Ready to Generate</span>
                      )}
                    </td>
                    <td className="text-right">
                      <div className="row" style={{ justifyContent: 'flex-end', gap: '6px' }}>
                        <button
                          className="btn primary sm"
                          disabled={busy === r.id}
                          onClick={() => generatePipeline(r)}
                        >
                          {busy === r.id ? '⚡ Generating…' : '⚡ Generate Pipeline'}
                        </button>
                        <Link className="btn ghost sm" to={`/repos/${r.id}`}>
                          Pipelines
                        </Link>
                        <Link className="btn ghost sm" to={`/repos/${r.id}/deployments`}>
                          Deployments
                        </Link>
                        <button
                          className="btn danger-ghost sm"
                          disabled={busy === r.id}
                          onClick={() => removeRepo(r)}
                          title="Remove repository"
                        >
                          🗑️
                        </button>
                      </div>
                    </td>
                  </tr>
                )
              })}

              {connected.length === 0 && (
                <tr>
                  <td colSpan="5" className="text-center muted" style={{ padding: '36px' }}>
                    No connected repositories yet. Connect a repository above to start synthesizing pipelines.
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
