import { useState, useEffect } from 'react';
import {
  FiSearch, FiPlay, FiCheckCircle, FiAlertCircle, FiAlertTriangle,
  FiInfo, FiClock, FiGitPullRequest, FiFolder, FiShield, FiCode,
  FiCopy, FiCheck, FiCpu, FiExternalLink, FiLayers
} from 'react-icons/fi';
import {
  getImportedRepos, runCodeReview, runPullRequestReview, getReviewHistory
} from '../api/github';
import DashboardLayout from '../components/DashboardLayout';
import './CodeReviewPage.css';

const SEVERITY_ICONS = {
  critical: <FiAlertCircle className="severity-icon critical" />,
  high: <FiAlertTriangle className="severity-icon high" />,
  medium: <FiAlertTriangle className="severity-icon medium" />,
  low: <FiInfo className="severity-icon low" />,
  info: <FiInfo className="severity-icon info" />,
};

export default function CodeReviewPage({ user }) {
  const [reviewMode, setReviewMode] = useState('repo'); // 'repo' | 'pr'
  const [repos, setRepos] = useState([]);
  const [selectedRepo, setSelectedRepo] = useState(null);
  const [prNumber, setPrNumber] = useState('');
  const [prDiff, setPrDiff] = useState('');
  const [prTitle, setPrTitle] = useState('');
  const [reviewing, setReviewing] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [history, setHistory] = useState([]);
  const [activeSeverityFilter, setActiveSeverityFilter] = useState('all');
  const [copiedComment, setCopiedComment] = useState(false);

  useEffect(() => {
    loadRepos();
    loadHistory();
  }, []);

  async function loadRepos() {
    try {
      const data = await getImportedRepos();
      const repoList = Array.isArray(data) ? data : [];
      setRepos(repoList);

      const params = new URLSearchParams(window.location.search);
      const ownerParam = params.get('owner');
      const repoParam = params.get('repo');
      if (ownerParam && repoParam) {
        const match = repoList.find((r) => r.owner === ownerParam && r.name === repoParam);
        if (match) setSelectedRepo(match);
      }
    } catch {
      setRepos([]);
    }
  }

  async function loadHistory() {
    try {
      const data = await getReviewHistory();
      setHistory(Array.isArray(data) ? data : []);
    } catch {
      setHistory([]);
    }
  }

  async function handleReview() {
    if (!selectedRepo) return;
    setReviewing(true);
    setResult(null);
    setError(null);

    try {
      let data;
      if (reviewMode === 'pr') {
        const num = parseInt(prNumber, 10) || 1;
        const diffText = prDiff.trim() || `diff --git a/src/main.py b/src/main.py\n--- a/src/main.py\n+++ b/src/main.py\n@@ -1,4 +1,6 @@\n+import os\n+password = "supersecret123"\n+eval("print('hello')")\n`;
        data = await runPullRequestReview(
          selectedRepo.owner,
          selectedRepo.name,
          num,
          diffText,
          prTitle || `PR #${num} Code Review`,
          user?.username || 'developer'
        );
      } else {
        data = await runCodeReview(selectedRepo.owner, selectedRepo.name, selectedRepo.defaultBranch);
      }
      setResult(data);
      loadHistory();
    } catch (err) {
      setError(err.message || 'Code review failed');
    } finally {
      setReviewing(false);
    }
  }

  function handleCopyComment() {
    if (result?.pr_comment_markdown) {
      navigator.clipboard.writeText(result.pr_comment_markdown);
      setCopiedComment(true);
      setTimeout(() => setCopiedComment(false), 2000);
    }
  }

  const verdictColors = {
    approve: 'success',
    approved: 'success',
    request_changes: 'error',
    comment: 'warning',
  };
  const verdictLabels = {
    approve: 'Approved',
    approved: 'Approved',
    request_changes: 'Changes Requested',
    comment: 'Comments & Suggestions',
  };

  const findings = result?.findings || [];
  const filteredFindings = activeSeverityFilter === 'all'
    ? findings
    : activeSeverityFilter === 'suggestions'
      ? findings.filter((f) => f.suggestion && f.suggestion.trim())
      : findings.filter((f) => f.severity?.toLowerCase() === activeSeverityFilter);

  return (
    <DashboardLayout user={user}>
      <div className="page-header animate-fade-in">
        <h1 className="page-title">
          <FiSearch size={28} /> <span className="text-gradient">AI Code Review Agent</span>
        </h1>
        <p className="page-subtitle">
          Multi-faceted static analysis (Semgrep + SonarQube) synthesized with Gemini LLM reasoning
        </p>
      </div>

      {/* Mode Selector Tabs */}
      <div className="review-mode-tabs animate-fade-in delay-1">
        <button
          className={`mode-tab-btn ${reviewMode === 'repo' ? 'active' : ''}`}
          onClick={() => setReviewMode('repo')}
          type="button"
        >
          <FiFolder size={16} /> Repository Audit Mode
        </button>
        <button
          className={`mode-tab-btn ${reviewMode === 'pr' ? 'active' : ''}`}
          onClick={() => setReviewMode('pr')}
          type="button"
        >
          <FiGitPullRequest size={16} /> Pull Request Diff Review
        </button>
      </div>

      {/* Repo & PR Selector */}
      <section className="review-selector glass-card animate-fade-in delay-2">
        <div className="selector-top-row">
          <h3>Select Target Repository</h3>
          {selectedRepo && (
            <span className="selected-tag">
              Target: <strong>{selectedRepo.owner}/{selectedRepo.name}</strong>
            </span>
          )}
        </div>

        <div className="repo-select-grid">
          {repos.map((repo) => (
            <button
              key={repo.id}
              className={`repo-select-card ${selectedRepo?.id === repo.id ? 'selected' : ''}`}
              onClick={() => {
                setSelectedRepo(repo);
                setResult(null);
                setError(null);
              }}
              type="button"
            >
              <span className="repo-select-name">{repo.name}</span>
              <span className="repo-select-owner">{repo.owner}</span>
            </button>
          ))}
          {repos.length === 0 && <p className="empty-hint">Import repositories from the Dashboard first.</p>}
        </div>

        {/* PR Inputs if PR mode */}
        {reviewMode === 'pr' && selectedRepo && (
          <div className="pr-input-container">
            <div className="pr-meta-row">
              <div className="input-group">
                <label>Pull Request Number</label>
                <input
                  type="number"
                  placeholder="e.g. 42"
                  value={prNumber}
                  onChange={(e) => setPrNumber(e.target.value)}
                  className="cr-input"
                />
              </div>
              <div className="input-group flex-1">
                <label>PR Title / Description (Optional)</label>
                <input
                  type="text"
                  placeholder="e.g. Feat: Add authentication and database service"
                  value={prTitle}
                  onChange={(e) => setPrTitle(e.target.value)}
                  className="cr-input"
                />
              </div>
            </div>

            <div className="input-group">
              <label>Custom Unified Diff (Optional — leave blank to simulate PR changes)</label>
              <textarea
                placeholder="diff --git a/app.py b/app.py&#10;+ password = 'admin'&#10;+ query = f'SELECT * FROM users WHERE id={id}'"
                value={prDiff}
                onChange={(e) => setPrDiff(e.target.value)}
                className="cr-textarea"
                rows={4}
              />
            </div>
          </div>
        )}

        {selectedRepo && (
          <div className="action-row">
            <button
              className="btn btn-primary btn-lg"
              onClick={handleReview}
              disabled={reviewing}
              id="btn-run-review"
            >
              {reviewing ? (
                <>
                  <span className="spinner" style={{ width: 18, height: 18, borderWidth: 2, borderTopColor: 'white' }}></span>
                  Analyzing with Semgrep + SonarQube + Gemini...
                </>
              ) : (
                <>
                  <FiPlay size={18} />
                  {reviewMode === 'pr' ? 'Run PR Code Review' : 'Run Full Repository Review'}
                </>
              )}
            </button>
          </div>
        )}
      </section>

      {error && (
        <div className="pipeline-error glass-card animate-fade-in">
          <FiAlertTriangle size={20} />
          <span>{error}</span>
        </div>
      )}

      {result && (
        <section className="review-result animate-fade-in">
          {/* Executive Summary & Verdict */}
          <div className="review-summary glass-card">
            <div className="review-verdict-row">
              <div className="verdict-left">
                <div className={`verdict-badge verdict-${verdictColors[result.verdict] || 'warning'}`}>
                  {result.verdict === 'approve' || result.verdict === 'approved' ? (
                    <FiCheckCircle size={18} />
                  ) : (
                    <FiAlertTriangle size={18} />
                  )}
                  {verdictLabels[result.verdict] || result.verdict}
                </div>
                <span className="engine-badge">
                  <FiShield size={13} /> Semgrep
                </span>
                <span className="engine-badge">
                  <FiLayers size={13} /> SonarQube
                </span>
                <span className="engine-badge">
                  <FiCpu size={13} /> Gemini LLM
                </span>
              </div>
              <div className="verdict-right">
                <span className="review-time">{result.generation_time_ms}ms</span>
                {result.pr_comment_markdown && (
                  <button className="btn btn-secondary btn-sm" onClick={handleCopyComment} type="button">
                    {copiedComment ? <FiCheck size={14} /> : <FiCopy size={14} />}
                    {copiedComment ? 'Copied PR Comment!' : 'Copy PR Comment'}
                  </button>
                )}
              </div>
            </div>

            <p className="review-summary-text">{result.summary}</p>

            {/* Severity Breakdown Bar */}
            <div className="severity-cards-grid">
              <div
                className={`sev-card critical ${activeSeverityFilter === 'critical' ? 'active' : ''}`}
                onClick={() => setActiveSeverityFilter(activeSeverityFilter === 'critical' ? 'all' : 'critical')}
              >
                <div className="sev-card-top">
                  <FiAlertCircle />
                  <span>Critical</span>
                </div>
                <div className="sev-card-num">{result.stats?.critical_count || 0}</div>
              </div>

              <div
                className={`sev-card high ${activeSeverityFilter === 'high' ? 'active' : ''}`}
                onClick={() => setActiveSeverityFilter(activeSeverityFilter === 'high' ? 'all' : 'high')}
              >
                <div className="sev-card-top">
                  <FiAlertTriangle />
                  <span>High</span>
                </div>
                <div className="sev-card-num">{result.stats?.high_count || 0}</div>
              </div>

              <div
                className={`sev-card medium ${activeSeverityFilter === 'medium' ? 'active' : ''}`}
                onClick={() => setActiveSeverityFilter(activeSeverityFilter === 'medium' ? 'all' : 'medium')}
              >
                <div className="sev-card-top">
                  <FiAlertTriangle />
                  <span>Medium</span>
                </div>
                <div className="sev-card-num">{result.stats?.medium_count || 0}</div>
              </div>

              <div
                className={`sev-card low ${activeSeverityFilter === 'low' ? 'active' : ''}`}
                onClick={() => setActiveSeverityFilter(activeSeverityFilter === 'low' ? 'all' : 'low')}
              >
                <div className="sev-card-top">
                  <FiInfo />
                  <span>Low / Info</span>
                </div>
                <div className="sev-card-num">{result.stats?.low_count || 0}</div>
              </div>

              <div
                className={`sev-card sugg ${activeSeverityFilter === 'suggestions' ? 'active' : ''}`}
                onClick={() => setActiveSeverityFilter(activeSeverityFilter === 'suggestions' ? 'all' : 'suggestions')}
              >
                <div className="sev-card-top">
                  <FiCode />
                  <span>Suggestions</span>
                </div>
                <div className="sev-card-num">{result.stats?.suggestions_count || 0}</div>
              </div>
            </div>
          </div>

          {/* Filter Pills */}
          <div className="filter-bar">
            <span className="filter-label">Filter by Severity:</span>
            {['all', 'critical', 'high', 'medium', 'low', 'suggestions'].map((f) => (
              <button
                key={f}
                className={`filter-pill ${activeSeverityFilter === f ? 'active' : ''}`}
                onClick={() => setActiveSeverityFilter(f)}
                type="button"
              >
                {f.charAt(0).toUpperCase() + f.slice(1)}
              </button>
            ))}
          </div>

          {/* Detailed Findings List */}
          <div className="review-findings glass-card">
            <div className="findings-header-row">
              <h3>Detailed Findings ({filteredFindings.length})</h3>
              <span className="findings-sub">
                {activeSeverityFilter === 'all' ? 'Showing all issues' : `Filtered by ${activeSeverityFilter}`}
              </span>
            </div>

            {filteredFindings.length === 0 ? (
              <div className="empty-findings">
                <FiCheckCircle size={32} className="text-success" />
                <p>No findings matching the selected filter criteria.</p>
              </div>
            ) : (
              <div className="findings-list">
                {filteredFindings.map((f, i) => (
                  <div key={i} className={`finding-card severity-${f.severity?.toLowerCase()}`}>
                    <div className="finding-header">
                      <div className="finding-header-left">
                        {SEVERITY_ICONS[f.severity?.toLowerCase()] || SEVERITY_ICONS.info}
                        <span className={`severity-label ${f.severity?.toLowerCase()}`}>{f.severity}</span>
                        <span className="source-tag">{f.source}</span>
                        {f.rule_id && <span className="rule-tag">{f.rule_id}</span>}
                        <span className="finding-title">{f.title}</span>
                      </div>
                      <span className="finding-location">
                        {f.file}{f.line ? `:${f.line}` : ''}
                      </span>
                    </div>

                    <p className="finding-desc">{f.description}</p>

                    {f.code_snippet && (
                      <div className="finding-code-box">
                        <div className="code-box-header">Code Snippet:</div>
                        <pre><code>{f.code_snippet}</code></pre>
                      </div>
                    )}

                    {f.suggestion && (
                      <div className="finding-suggestion">
                        <strong>💡 Actionable Remediation:</strong> {f.suggestion}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        </section>
      )}

      {/* Historical Reviews */}
      {history.length > 0 && (
        <section className="review-history glass-card animate-fade-in delay-3">
          <h3>Recent Code Reviews</h3>
          <div className="history-table-wrapper">
            <table className="history-table">
              <thead>
                <tr>
                  <th>Repository</th>
                  <th>Verdict</th>
                  <th>Files</th>
                  <th>Findings</th>
                  <th>Date</th>
                </tr>
              </thead>
              <tbody>
                {history.slice(0, 8).map((h) => (
                  <tr key={h.id}>
                    <td>
                      <span className="history-repo">{h.owner}/{h.repoName}</span>
                    </td>
                    <td>
                      <span className={`verdict-chip verdict-${verdictColors[h.verdict] || 'info'}`}>
                        {verdictLabels[h.verdict] || h.verdict}
                      </span>
                    </td>
                    <td>{h.filesReviewed || 0}</td>
                    <td>{h.findingsCount || 0}</td>
                    <td>
                      <span className="history-date">
                        <FiClock size={12} /> {h.createdAt ? new Date(h.createdAt).toLocaleDateString() : 'N/A'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </DashboardLayout>
  );
}
