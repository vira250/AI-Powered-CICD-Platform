import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import api from '../api.js'

export default function RepoDetail() {
  const { id } = useParams()
  const [pipelines, setPipelines] = useState([])
  const [runs, setRuns] = useState([])
  const [reports, setReports] = useState([])
  const [selectedYaml, setSelectedYaml] = useState('')
  const [busy, setBusy] = useState(null)
  const [message, setMessage] = useState('')

  const load = useCallback(async () => {
    const [p, r, a] = await Promise.all([
      api.get(`/repos/${id}/pipelines`),
      api.get(`/repos/${id}/runs`).catch(() => ({ data: [] })),
      api.get(`/repos/${id}/reports`),
    ])
    setPipelines(p.data)
    setRuns(r.data)
    setReports(a.data)
  }, [id])

  useEffect(() => { load() }, [load])

  const showYaml = async (pipelineId) => {
    const { data } = await api.get(`/pipelines/${pipelineId}`)
    setSelectedYaml(data.workflowYaml)
  }

  const analyze = async (runId) => {
    setBusy(runId)
    setMessage('')
    try {
      const { data } = await api.post(`/repos/${id}/runs/${runId}/analyze`)
      setMessage(`Analysis saved (report #${data.reportId}, confidence ${data.analysis?.confidence}%)`)
      await load()
    } catch (e) {
      setMessage(`Analysis failed: ${e.response?.data?.error || e.message}`)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div>
      <h2 id="pipelines">Pipelines</h2>
      {message && <p className="notice">{message}</p>}
      <section className="card">
        <table>
          <thead><tr><th>#</th><th>Template</th><th>Stack</th><th>Status</th><th>Created</th><th /></tr></thead>
          <tbody>
            {pipelines.map((p) => (
              <tr key={p.id}>
                <td>{p.id}</td>
                <td>{p.templateUsed}</td>
                <td><code>{p.stackJson}</code></td>
                <td><span className={`badge ${p.status.toLowerCase()}`}>{p.status}</span></td>
                <td>{new Date(p.createdAt).toLocaleString()}</td>
                <td><button className="btn" onClick={() => showYaml(p.id)}>View YAML</button></td>
              </tr>
            ))}
            {pipelines.length === 0 && (
              <tr><td colSpan="6" className="muted">No pipelines yet — use “Generate Pipeline” on the dashboard.</td></tr>
            )}
          </tbody>
        </table>
        {selectedYaml && (
          <pre className="yaml-view">{selectedYaml}</pre>
        )}
      </section>

      <h2>GitHub Actions runs</h2>
      <section className="card" id="logs">
        <table>
          <thead><tr><th>Run</th><th>Workflow</th><th>Status</th><th>Conclusion</th><th>When</th><th /></tr></thead>
          <tbody>
            {runs.map((r) => (
              <tr key={r.id}>
                <td><a href={r.html_url} target="_blank" rel="noreferrer">#{r.run_number}</a></td>
                <td>{r.name}</td>
                <td>{r.status}</td>
                <td>
                  <span className={`badge ${r.conclusion === 'success' ? 'success' : r.conclusion === 'failure' ? 'failed' : ''}`}>
                    {r.conclusion || '—'}
                  </span>
                </td>
                <td>{new Date(r.created_at).toLocaleString()}</td>
                <td>
                  {r.conclusion === 'failure' && (
                    <button className="btn" disabled={busy === r.id} onClick={() => analyze(r.id)}>
                      {busy === r.id ? 'Analysing…' : 'AI Analysis'}
                    </button>
                  )}
                </td>
              </tr>
            ))}
            {runs.length === 0 && (
              <tr><td colSpan="6" className="muted">No workflow runs found (push the generated YAML first).</td></tr>
            )}
          </tbody>
        </table>
      </section>

      <h2>AI analysis reports</h2>
      <section className="card">
        {reports.map((r) => (
          <details key={r.id} className="report">
            <summary>
              <span className="badge">{r.agent}</span>{' '}
              {r.rootCause || 'report'} — {new Date(r.createdAt).toLocaleString()}
              {r.confidence != null && <span className="muted"> · confidence {r.confidence}%</span>}
              {r.simpleFix && <span className="badge warn"> auto-fixable</span>}
            </summary>
            {r.errorExcerpt && <pre className="yaml-view">{r.errorExcerpt}</pre>}
            {r.impact && <p><strong>Impact:</strong> {r.impact}</p>}
            {r.suggestedFix && <p><strong>Suggested fix:</strong> {r.suggestedFix}</p>}
          </details>
        ))}
        {reports.length === 0 && <p className="muted">No analysis reports yet.</p>}
      </section>
    </div>
  )
}
