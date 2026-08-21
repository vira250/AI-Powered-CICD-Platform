import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import api from '../api.js'

function StackSpecCell({ stackJson }) {
  const [open, setOpen] = useState(false)

  let parsed = null
  try {
    parsed = typeof stackJson === 'string' ? JSON.parse(stackJson) : stackJson
  } catch (e) {
    parsed = null
  }

  const mainBadges = []
  if (parsed && typeof parsed === 'object') {
    if (parsed.language) mainBadges.push(parsed.language)
    if (parsed.framework && parsed.framework !== 'none') mainBadges.push(parsed.framework)
    if (parsed.build_tool) mainBadges.push(parsed.build_tool)
    const ver = parsed.version || parsed.java_version || parsed.python_version || parsed.node_version || parsed.go_version
    if (ver) mainBadges.push(`v${ver}`)
  }

  const rawPreview = typeof stackJson === 'string' ? stackJson : JSON.stringify(stackJson)
  const isLong = rawPreview.length > 25 || (parsed && Object.keys(parsed).length > 2)

  return (
    <div style={{ maxWidth: '240px' }}>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: '4px', alignItems: 'center' }}>
        {mainBadges.length > 0 ? (
          mainBadges.slice(0, 3).map((b, i) => (
            <span key={i} className="badge muted-badge font-mono" style={{ fontSize: '11px', padding: '2px 6px' }}>
              {b}
            </span>
          ))
        ) : (
          <code
            style={{
              fontSize: '11.5px',
              color: 'var(--text-secondary)',
              maxWidth: '160px',
              overflow: 'hidden',
              textOverflow: 'ellipsis',
              whiteSpace: 'nowrap',
              display: 'inline-block',
            }}
          >
            {rawPreview || '{}'}
          </code>
        )}

        {isLong && (
          <button
            type="button"
            className="btn ghost sm"
            style={{
              padding: '1px 6px',
              fontSize: '10.5px',
              color: 'var(--brand-primary)',
              borderRadius: '4px',
              height: '20px',
            }}
            onClick={() => setOpen(!open)}
          >
            {open ? '▲ Less' : '▼ More'}
          </button>
        )}
      </div>

      {open && (
        <div
          style={{
            marginTop: '8px',
            padding: '8px 10px',
            background: 'var(--bg-canvas)',
            border: '1px solid var(--border-medium)',
            borderRadius: '6px',
            fontSize: '11px',
            fontFamily: 'JetBrains Mono, monospace',
            maxHeight: '140px',
            overflowY: 'auto',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            color: '#a5b4fc',
            lineHeight: 1.4,
          }}
        >
          {parsed ? JSON.stringify(parsed, null, 2) : rawPreview}
        </div>
      )}
    </div>
  )
}

export default function RepoDetail() {
  const { id } = useParams()
  const [repo, setRepo] = useState(null)
  const [pipelines, setPipelines] = useState([])
  const [runs, setRuns] = useState([])
  const [reports, setReports] = useState([])
  const [selectedYaml, setSelectedYaml] = useState('')
  const [selectedPipelineId, setSelectedPipelineId] = useState(null)
  const [copied, setCopied] = useState(false)
  const [busy, setBusy] = useState(null)
  const [message, setMessage] = useState('')

  const load = useCallback(async () => {
    const [reposResp, p, r, a] = await Promise.all([
      api.get('/repos').catch(() => ({ data: [] })),
      api.get(`/repos/${id}/pipelines`),
      api.get(`/repos/${id}/runs`).catch(() => ({ data: [] })),
      api.get(`/repos/${id}/reports`),
    ])
    const foundRepo = (reposResp.data || []).find((c) => String(c.id) === String(id))
    setRepo(foundRepo)
    setPipelines(p.data || [])
    setRuns(r.data || [])
    setReports(a.data || [])
  }, [id])

  useEffect(() => { load() }, [load])

  const showYaml = async (pipelineId) => {
    const { data } = await api.get(`/pipelines/${pipelineId}`)
    setSelectedYaml(data.workflowYaml)
    setSelectedPipelineId(pipelineId)
  }

  const copyYaml = () => {
    if (!selectedYaml) return
    navigator.clipboard.writeText(selectedYaml)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  const analyze = async (runId) => {
    setBusy(runId)
    setMessage('')
    try {
      const { data } = await api.post(`/repos/${id}/runs/${runId}/analyze`)
      if (data.autoPushed) {
        setMessage(`✨ Log Agent diagnosed the failure and Pipeline Agent auto-healed the workflow & pushed to GitHub (Pipeline #${data.newPipelineId})!`)
      } else {
        setMessage(`Analysis report saved (report #${data.reportId}, confidence ${data.analysis?.confidence}%)`)
      }
      await load()
    } catch (e) {
      setMessage(`Analysis failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
    }
  }

  const generatePipeline = async () => {
    setBusy('generating')
    setMessage('')
    try {
      const { data } = await api.post(`/repos/${id}/generate-pipeline`)
      const costStr = data.creditsUsed != null ? `$${Number(data.creditsUsed).toFixed(5)}` : 'Free'
      const tokensStr = data.totalTokens ? ` · ${data.totalTokens} tokens` : ''
      setMessage(`🎉 Pipeline #${data.pipelineId} synthesized & pushed to GitHub! (${data.templateUsed}) — Cost: ${costStr}${tokensStr}`)
      await load()
    } catch (e) {
      setMessage(`Pipeline generation failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
    }
  }

  const totalCost = pipelines.reduce((sum, p) => sum + (p.creditsUsed || 0), 0)
  const totalTokens = pipelines.reduce((sum, p) => sum + (p.totalTokens || 0), 0)

  return (
    <div>
      {/* Breadcrumbs */}
      <div className="breadcrumbs">
        <Link to="/">Dashboard</Link>
        <span className="breadcrumb-separator">/</span>
        <span>Repositories</span>
        <span className="breadcrumb-separator">/</span>
        <span style={{ color: 'var(--text-primary)', fontWeight: 600 }}>
          {repo?.fullName || `Repo #${id}`}
        </span>
      </div>

      {/* Page Header */}
      <div className="page-header">
        <div>
          <h1 className="page-title">{repo?.fullName || `Repository #${id}`}</h1>
          <p className="page-subtitle">
            Default Branch: <span className="branch-tag">{repo?.defaultBranch || 'main'}</span>
            <span style={{ margin: '0 8px' }}>·</span>
            Visibility: <span className={`badge ${repo?.isPrivate ? 'private' : 'public'}`}>{repo?.isPrivate ? 'Private' : 'Public'}</span>
          </p>
        </div>

        <div className="row" style={{ gap: '8px' }}>
          <button className="btn primary" disabled={busy === 'generating'} onClick={generatePipeline}>
            {busy === 'generating' ? '⚡ Generating AI Pipeline…' : '⚡ Generate Pipeline'}
          </button>
          <Link className="btn ghost" to={`/repos/${id}/deployments`}>
            Deployments
          </Link>
        </div>
      </div>

      {message && (
        <div className="notice-toast">
          <span>{message}</span>
          <button className="btn sm ghost" onClick={() => setMessage('')} style={{ color: 'inherit' }}>✕</button>
        </div>
      )}

      {/* Pipelines Section */}
      <section className="card">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
              Synthesized Pipelines
            </h2>
            <p className="card-subtitle">AI-generated GitHub Actions workflow history</p>
          </div>

          {pipelines.length > 0 && (
            <div className="agent-status-pill">
              <span style={{ color: 'var(--signal-success)', fontWeight: 600 }}>
                ${totalCost.toFixed(5)}
              </span>
              <span className="muted" style={{ fontSize: '11px', marginLeft: '6px' }}>
                {totalTokens.toLocaleString()} tokens
              </span>
            </div>
          )}
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Template</th>
                <th>Stack Specification</th>
                <th>Status</th>
                <th>Cost & Tokens</th>
                <th>Created</th>
                <th className="text-right">Action</th>
              </tr>
            </thead>
            <tbody>
              {pipelines.map((p) => (
                <tr key={p.id}>
                  <td><strong>#{p.id}</strong></td>
                  <td>
                    <span className="badge muted-badge font-mono">{p.templateUsed}</span>
                  </td>
                  <td>
                    <StackSpecCell stackJson={p.stackJson} />
                  </td>
                  <td>
                    <span className={`badge ${p.status.toLowerCase()}`}>{p.status}</span>
                  </td>
                  <td>
                    <div style={{ fontWeight: 600, color: p.creditsUsed > 0 ? '#10b981' : 'inherit' }}>
                      {p.creditsUsed != null ? `$${p.creditsUsed.toFixed(5)}` : '—'}
                    </div>
                    {p.totalTokens != null && p.totalTokens > 0 && (
                      <div className="muted" style={{ fontSize: '11px' }}>
                        {p.totalTokens.toLocaleString()} tokens
                      </div>
                    )}
                  </td>
                  <td>{new Date(p.createdAt).toLocaleString()}</td>
                  <td className="text-right">
                    <button className="btn sm" onClick={() => showYaml(p.id)}>
                      {selectedPipelineId === p.id ? 'Viewing YAML' : 'View YAML'}
                    </button>
                  </td>
                </tr>
              ))}
              {pipelines.length === 0 && (
                <tr>
                  <td colSpan="7" className="text-center muted" style={{ padding: '36px' }}>
                    No pipelines synthesized yet — click "Generate Pipeline" to create an AI workflow.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Code Window for YAML Preview */}
        {selectedYaml && (
          <div className="code-window">
            <div className="code-window-header">
              <div className="row" style={{ gap: '8px' }}>
                <div className="code-window-dots">
                  <span className="code-dot red"></span>
                  <span className="code-dot yellow"></span>
                  <span className="code-dot green"></span>
                </div>
                <span className="code-window-title">.github/workflows/ai-ci-cd.yml (Pipeline #{selectedPipelineId})</span>
              </div>
              <button className="btn sm ghost" onClick={copyYaml}>
                {copied ? '✓ Copied!' : '📋 Copy YAML'}
              </button>
            </div>
            <pre className="code-content">{selectedYaml}</pre>
          </div>
        )}
      </section>

      {/* GitHub Actions Runs */}
      <section className="card" id="logs">
        <div className="card-header">
          <div>
            <h2 className="card-title">
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>
              GitHub Actions Runs
            </h2>
            <p className="card-subtitle">Live execution runs received via GitHub Webhook</p>
          </div>
        </div>

        <div className="table-container">
          <table>
            <thead>
              <tr>
                <th>Run #</th>
                <th>Workflow Name</th>
                <th>Status</th>
                <th>Conclusion</th>
                <th>Execution Time</th>
                <th className="text-right">AI Self-Healing</th>
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.id}>
                  <td>
                    <a
                      href={r.html_url}
                      target="_blank"
                      rel="noreferrer"
                      style={{ color: 'var(--brand-primary)', textDecoration: 'none', fontWeight: 600 }}
                    >
                      #{r.run_number} ↗
                    </a>
                  </td>
                  <td><strong>{r.name}</strong></td>
                  <td>
                    <span className="badge muted-badge">{r.status}</span>
                  </td>
                  <td>
                    <span className={`badge ${r.conclusion === 'success' ? 'success' : r.conclusion === 'failure' ? 'failed' : 'muted-badge'}`}>
                      {r.conclusion === 'success' ? '✓ Success' : r.conclusion === 'failure' ? '✗ Failed' : (r.conclusion || 'In Progress')}
                    </span>
                  </td>
                  <td>{new Date(r.created_at).toLocaleString()}</td>
                  <td className="text-right">
                    {r.conclusion === 'failure' && (
                      <button className="btn primary sm" disabled={busy === r.id} onClick={() => analyze(r.id)}>
                        {busy === r.id ? 'Diagnosing…' : '⚡ AI Diagnose & Fix'}
                      </button>
                    )}
                  </td>
                </tr>
              ))}
              {runs.length === 0 && (
                <tr>
                  <td colSpan="6" className="text-center muted" style={{ padding: '36px' }}>
                    No workflow runs recorded yet. Push changes or generate a pipeline to trigger GitHub Actions.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {/* AI Diagnostic Reports */}
      {reports.length > 0 && (
        <section className="card">
          <div className="card-header">
            <div>
              <h2 className="card-title">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
                AI Diagnostic & Remediation Reports
              </h2>
              <p className="card-subtitle">Automated root-cause analysis and self-healing logs from Log Analysis Agent</p>
            </div>
          </div>

          <div>
            {reports.map((r) => (
              <div key={r.id} className="diag-card">
                <div className="diag-card-header">
                  <div className="row" style={{ gap: '10px' }}>
                    <span className="badge generated">{r.agent}</span>
                    <strong style={{ color: 'var(--text-primary)' }}>
                      {r.rootCause || 'Failure Analysis Report'}
                    </strong>
                  </div>
                  <div className="row" style={{ gap: '8px' }}>
                    {r.confidence != null && (
                      <span className="badge success">{r.confidence}% Confidence</span>
                    )}
                    {r.simpleFix && (
                      <span className="badge public">Auto-Remediated</span>
                    )}
                    <span className="muted" style={{ fontSize: '12px' }}>
                      {new Date(r.createdAt).toLocaleString()}
                    </span>
                  </div>
                </div>

                <div className="diag-body">
                  {r.impact && (
                    <div className="diag-section">
                      <div className="diag-section-title">Impact Assessment</div>
                      <p style={{ margin: 0, color: 'var(--text-secondary)' }}>{r.impact}</p>
                    </div>
                  )}

                  {r.suggestedFix && (
                    <div className="diag-section">
                      <div className="diag-section-title">Suggested Remediation</div>
                      <div className="diag-fix-box">
                        💡 {r.suggestedFix}
                      </div>
                    </div>
                  )}

                  {r.errorExcerpt && (
                    <div className="diag-section">
                      <div className="diag-section-title">Build Log Excerpt</div>
                      <pre className="code-content" style={{ borderRadius: '8px', maxHeight: '180px', overflowY: 'auto' }}>
                        {r.errorExcerpt}
                      </pre>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}
