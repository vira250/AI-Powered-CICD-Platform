import { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  FiBox, FiPackage, FiGitBranch, FiPlay, FiSearch, FiFileText, FiServer,
  FiCheckCircle, FiAlertCircle, FiAlertTriangle, FiClock, FiPlus,
  FiExternalLink, FiTrash2, FiFolder, FiArrowRight, FiActivity,
  FiCpu, FiShield, FiRefreshCw, FiZap, FiLayers, FiTerminal
} from 'react-icons/fi';
import {
  getImportedRepos, getSelectedGithubRepository, removeImportedRepo,
  getPipelineHistory, getReviewHistory
} from '../api/github';
import DashboardLayout from '../components/DashboardLayout';
import RepoImportModal from '../components/RepoImportModal';
import GithubRepositorySelector from '../components/GithubRepositorySelector';
import './DashboardPage.css';

function normalizeRepository(repo) {
  return {
    id: repo.id,
    name: repo.name,
    full_name: repo.full_name || repo.fullName || `${repo.owner?.login || repo.owner || ''}/${repo.name}`,
    private: Boolean(repo.private ?? repo.privateRepository),
    html_url: repo.html_url || repo.htmlUrl || '',
    default_branch: repo.default_branch || repo.defaultBranch || 'main',
    description: repo.description || '',
    language: repo.language || '',
    owner: {
      login: repo.owner?.login || repo.owner_login || repo.ownerLogin || repo.owner || '',
    },
  };
}

export default function DashboardPage({ user }) {
  const [importedRepos, setImportedRepos] = useState([]);
  const [selectedRepo, setSelectedRepo] = useState(null);
  const [pipelineRuns, setPipelineRuns] = useState([]);
  const [codeReviews, setCodeReviews] = useState([]);
  const [agentStatus, setAgentStatus] = useState('checking'); // 'online', 'offline', 'checking'
  const [agentDetails, setAgentDetails] = useState(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [activeActivityTab, setActiveActivityTab] = useState('pipelines'); // 'pipelines' | 'reviews'
  const [isImportModalOpen, setIsImportModalOpen] = useState(false);
  const [loading, setLoading] = useState(true);

  const navigate = useNavigate();

  useEffect(() => {
    loadAllDashboardData();
  }, []);

  async function loadAllDashboardData() {
    setLoading(true);
    await Promise.allSettled([
      fetchImportedRepos(),
      fetchSelectedRepo(),
      fetchPipelineHistory(),
      fetchReviewHistory(),
      checkAgentHealth(),
    ]);
    setLoading(false);
  }

  async function fetchImportedRepos() {
    try {
      const data = await getImportedRepos();
      setImportedRepos(Array.isArray(data) ? data : []);
    } catch {
      setImportedRepos([]);
    }
  }

  async function fetchSelectedRepo() {
    try {
      const data = await getSelectedGithubRepository();
      if (data && data.id) {
        setSelectedRepo(normalizeRepository(data));
      }
    } catch {
      // none selected
    }
  }

  async function fetchPipelineHistory() {
    try {
      const data = await getPipelineHistory();
      setPipelineRuns(Array.isArray(data) ? data : []);
    } catch {
      setPipelineRuns([]);
    }
  }

  async function fetchReviewHistory() {
    try {
      const data = await getReviewHistory();
      setCodeReviews(Array.isArray(data) ? data : []);
    } catch {
      setCodeReviews([]);
    }
  }

  async function checkAgentHealth() {
    setAgentStatus('checking');
    try {
      const res = await fetch('http://localhost:8001/health');
      if (res.ok) {
        const data = await res.json();
        setAgentStatus('online');
        setAgentDetails(data);
      } else {
        setAgentStatus('offline');
      }
    } catch {
      setAgentStatus('offline');
    }
  }

  async function handleRemoveImport(e, id) {
    e.stopPropagation();
    if (confirm('Are you sure you want to remove this repository from your imported projects?')) {
      try {
        await removeImportedRepo(id);
        setImportedRepos((prev) => prev.filter((r) => r.id !== id));
      } catch {
        alert('Failed to remove repository');
      }
    }
  }

  function handleRepositorySelected(repo) {
    setSelectedRepo(normalizeRepository(repo));
  }

  function handleRepoImported() {
    fetchImportedRepos();
  }

  // Filtered repositories based on user search
  const filteredRepos = useMemo(() => {
    if (!searchQuery.trim()) return importedRepos;
    const q = searchQuery.toLowerCase();
    return importedRepos.filter(
      (r) =>
        (r.name && r.name.toLowerCase().includes(q)) ||
        (r.owner && r.owner.toLowerCase().includes(q)) ||
        (r.language && r.language.toLowerCase().includes(q))
    );
  }, [importedRepos, searchQuery]);

  // Derived KPI metrics
  const successfulRuns = pipelineRuns.filter((r) => r.status === 'success').length;
  const pipelineSuccessRate = pipelineRuns.length > 0 ? Math.round((successfulRuns / pipelineRuns.length) * 100) : 100;
  const totalFindings = codeReviews.reduce((acc, cr) => acc + (cr.findingsCount || 0), 0);

  return (
    <DashboardLayout user={user}>
      <div className="prod-dashboard animate-fade-in" id="production-dashboard">
        {/* Top Hero Banner */}
        <header className="dashboard-hero">
          <div className="dashboard-hero-content">
            <div className="hero-badge-cluster">
              <div className={`status-pill status-${agentStatus}`}>
                {agentStatus === 'online' ? <FiCheckCircle size={13} /> : <FiAlertCircle size={13} />}
                <span>AI Agents: {agentStatus === 'online' ? 'Online (8001)' : 'Offline'}</span>
              </div>
              <div className="status-pill status-model">
                <FiZap size={13} />
                <span>Model: gemini-3.6-flash</span>
              </div>
              <div className="status-pill status-backend">
                <FiShield size={13} />
                <span>Backend: Spring Boot 3.3</span>
              </div>
            </div>

            <h1 className="hero-title">
              CI/CD <span className="text-gradient">Operations Center</span>
            </h1>
            <p className="hero-subtitle">
              Intelligent repository orchestration, automated GitHub Actions generation, LLM code review, and containerized deployment planning.
            </p>
          </div>

          <div className="dashboard-hero-actions">
            <button
              className="btn btn-secondary btn-refresh-dash"
              onClick={loadAllDashboardData}
              title="Refresh telemetry"
              type="button"
            >
              <FiRefreshCw size={15} className={loading ? 'spin' : ''} />
              Sync Telemetry
            </button>
            <button
              className="btn btn-primary btn-lg"
              onClick={() => setIsImportModalOpen(true)}
              type="button"
              id="btn-hero-import-repo"
            >
              <FiPlus size={18} />
              Import Repository
            </button>
          </div>
        </header>

        {/* 4 KPI Metric Cards */}
        <section className="kpi-grid">
          {/* KPI 1: Repositories */}
          <div className="kpi-card glass-card">
            <div className="kpi-icon-wrapper kpi-purple">
              <FiBox size={22} />
            </div>
            <div className="kpi-info">
              <span className="kpi-label">Tracked Repositories</span>
              <div className="kpi-value-row">
                <span className="kpi-value">{importedRepos.length}</span>
                <span className="kpi-subtext">connected repos</span>
              </div>
            </div>
            <div className="kpi-footer">
              <span className="kpi-detail">
                {importedRepos.filter((r) => r.language).length} with detected language
              </span>
            </div>
          </div>

          {/* KPI 2: Pipeline Success */}
          <div className="kpi-card glass-card">
            <div className="kpi-icon-wrapper kpi-blue">
              <FiGitBranch size={22} />
            </div>
            <div className="kpi-info">
              <span className="kpi-label">Generated Workflows</span>
              <div className="kpi-value-row">
                <span className="kpi-value">{pipelineRuns.length}</span>
                <span className="kpi-badge kpi-badge-success">{pipelineSuccessRate}% success</span>
              </div>
            </div>
            <div className="kpi-footer">
              <span className="kpi-detail">
                {successfulRuns} valid GitHub Actions generated
              </span>
            </div>
          </div>

          {/* KPI 3: Code Reviews */}
          <div className="kpi-card glass-card">
            <div className="kpi-icon-wrapper kpi-amber">
              <FiSearch size={22} />
            </div>
            <div className="kpi-info">
              <span className="kpi-label">AI Code Reviews</span>
              <div className="kpi-value-row">
                <span className="kpi-value">{codeReviews.length}</span>
                <span className="kpi-subtext">{totalFindings} findings caught</span>
              </div>
            </div>
            <div className="kpi-footer">
              <span className="kpi-detail">Automated security & bug audits</span>
            </div>
          </div>

          {/* KPI 4: Infrastructure & Deployments */}
          <div className="kpi-card glass-card">
            <div className="kpi-icon-wrapper kpi-green">
              <FiServer size={22} />
            </div>
            <div className="kpi-info">
              <span className="kpi-label">Deployment Architecture</span>
              <div className="kpi-value-row">
                <span className="kpi-value">Docker</span>
                <span className="kpi-badge kpi-badge-ready">Ready</span>
              </div>
            </div>
            <div className="kpi-footer">
              <span className="kpi-detail">Dockerfile & Compose planner active</span>
            </div>
          </div>
        </section>

        {/* AI Launchpad — Quick Actions Hub */}
        <section className="launchpad-section">
          <div className="section-header-compact">
            <div className="section-title-wrap">
              <FiZap className="accent-icon" />
              <h2 className="section-title">AI Agents Launchpad</h2>
            </div>
            <span className="section-tagline">Trigger intelligent autonomous workflows</span>
          </div>

          <div className="launchpad-grid">
            <div
              className="launchpad-card glass-card"
              onClick={() => navigate('/pipelines')}
              role="button"
              tabIndex={0}
            >
              <div className="launchpad-card-header">
                <div className="agent-badge-icon purple">
                  <FiGitBranch size={20} />
                </div>
                <span className="launchpad-tag">Autonomous</span>
              </div>
              <h3 className="launchpad-title">Pipeline Generation Agent</h3>
              <p className="launchpad-desc">
                Analyzes repo dependencies, detect build tools, and synthesizes production-grade GitHub Actions YAML with validation.
              </p>
              <div className="launchpad-action">
                <span>Launch Agent</span>
                <FiArrowRight size={16} />
              </div>
            </div>

            <div
              className="launchpad-card glass-card"
              onClick={() => navigate('/reviews')}
              role="button"
              tabIndex={0}
            >
              <div className="launchpad-card-header">
                <div className="agent-badge-icon blue">
                  <FiSearch size={20} />
                </div>
                <span className="launchpad-tag">LLM Audit</span>
              </div>
              <h3 className="launchpad-title">Code Review & Security Agent</h3>
              <p className="launchpad-desc">
                Performs deep static analysis, finding syntax issues, security vulnerabilities, and bug patterns with actionable fixes.
              </p>
              <div className="launchpad-action">
                <span>Review Code</span>
                <FiArrowRight size={16} />
              </div>
            </div>

            <div
              className="launchpad-card glass-card"
              onClick={() => navigate('/logs')}
              role="button"
              tabIndex={0}
            >
              <div className="launchpad-card-header">
                <div className="agent-badge-icon rose">
                  <FiFileText size={20} />
                </div>
                <span className="launchpad-tag">Failure Analysis</span>
              </div>
              <h3 className="launchpad-title">Log Analysis & Root Cause</h3>
              <p className="launchpad-desc">
                Parses build & test failure logs, extracts stack traces, computes failure confidence, and suggests exact code patches.
              </p>
              <div className="launchpad-action">
                <span>Analyze Logs</span>
                <FiArrowRight size={16} />
              </div>
            </div>

            <div
              className="launchpad-card glass-card"
              onClick={() => navigate('/deployments')}
              role="button"
              tabIndex={0}
            >
              <div className="launchpad-card-header">
                <div className="agent-badge-icon green">
                  <FiServer size={20} />
                </div>
                <span className="launchpad-tag">Cloud Ready</span>
              </div>
              <h3 className="launchpad-title">Deployment Planning Agent</h3>
              <p className="launchpad-desc">
                Generates optimized multi-stage Dockerfiles, docker-compose orchestration, environment configurations, and rollback strategy.
              </p>
              <div className="launchpad-action">
                <span>Plan Deployment</span>
                <FiArrowRight size={16} />
              </div>
            </div>
          </div>
        </section>

        {/* Selected Repo Panel (If active) */}
        {selectedRepo && (
          <section className="selected-repo-panel glass-card animate-fade-in">
            <div className="selected-repo-copy">
              <span className="selected-repo-label">Active Inspected Repository</span>
              <h2 className="selected-repo-title">{selectedRepo.name}</h2>
              <p className="selected-repo-meta">
                Owner: <strong>{selectedRepo.owner?.login}</strong> · Branch: <code>{selectedRepo.default_branch}</code> · {selectedRepo.private ? '🔒 Private' : '🌐 Public'}
              </p>
            </div>

            <div className="selected-repo-actions">
              <button
                className="btn btn-secondary"
                onClick={() => setSelectedRepo(null)}
                type="button"
              >
                Clear
              </button>
              <button
                className="btn btn-secondary"
                onClick={() => navigate(`/pipelines?owner=${selectedRepo.owner?.login}&repo=${selectedRepo.name}`)}
                type="button"
              >
                <FiGitBranch size={15} />
                Generate CI/CD
              </button>
              <button
                className="btn btn-primary"
                onClick={() => navigate(`/repo/${selectedRepo.owner?.login}/${selectedRepo.name}`)}
                type="button"
              >
                Inspect Files & Tree
                <FiArrowRight size={16} />
              </button>
            </div>
          </section>
        )}

        {/* Main Two-Column Layout */}
        <div className="dashboard-main-grid">
          {/* Left Column: Repositories Showcase & GitHub Importer */}
          <div className="main-left-column">
            {/* Repositories Section */}
            <section className="repos-container glass-card">
              <div className="repos-header-bar">
                <div className="repos-header-title">
                  <FiBox size={19} />
                  <h3>Imported Repositories</h3>
                  <span className="badge-count">{filteredRepos.length}</span>
                </div>

                <div className="repos-search-wrap">
                  <FiSearch className="search-input-icon" size={15} />
                  <input
                    type="text"
                    className="repos-search-input"
                    placeholder="Search by repo or language..."
                    value={searchQuery}
                    onChange={(e) => setSearchQuery(e.target.value)}
                  />
                  {searchQuery && (
                    <button
                      className="search-clear-btn"
                      onClick={() => setSearchQuery('')}
                      type="button"
                    >
                      ×
                    </button>
                  )}
                </div>
              </div>

              {filteredRepos.length > 0 ? (
                <div className="prod-repo-grid">
                  {filteredRepos.map((repo) => (
                    <div
                      key={repo.id}
                      className="prod-repo-card"
                      onClick={() => navigate(`/repo/${repo.owner}/${repo.name}`)}
                      role="button"
                      tabIndex={0}
                    >
                      <div className="prod-repo-card-top">
                        <div className="repo-title-block">
                          <span className="repo-name-text">{repo.name}</span>
                          <span className="repo-owner-tag">{repo.owner}</span>
                        </div>
                        <div className="repo-card-actions" onClick={(e) => e.stopPropagation()}>
                          <button
                            className="repo-action-icon-btn danger"
                            onClick={(e) => handleRemoveImport(e, repo.id)}
                            title="Untrack repository"
                            type="button"
                          >
                            <FiTrash2 size={14} />
                          </button>
                        </div>
                      </div>

                      <p className="repo-desc-text">
                        {repo.description || 'Repository tracked in CI/CD pipeline automation workspace.'}
                      </p>

                      <div className="repo-tags-row">
                        <span className="repo-branch-pill">
                          <FiGitBranch size={11} />
                          {repo.defaultBranch || 'main'}
                        </span>
                        {repo.language && (
                          <span className="repo-lang-pill">
                            <span className="lang-dot"></span>
                            {repo.language}
                          </span>
                        )}
                        <span className={`repo-vis-pill ${repo.private ? 'private' : 'public'}`}>
                          {repo.private ? 'Private' : 'Public'}
                        </span>
                      </div>

                      {/* Quick Ops Buttons on each card */}
                      <div className="repo-card-quick-ops" onClick={(e) => e.stopPropagation()}>
                        <button
                          className="btn-quick-op"
                          onClick={() => navigate(`/pipelines?owner=${repo.owner}&repo=${repo.name}`)}
                          title="Generate Pipeline for this repo"
                          type="button"
                        >
                          <FiGitBranch size={12} />
                          CI/CD
                        </button>
                        <button
                          className="btn-quick-op"
                          onClick={() => navigate(`/reviews?owner=${repo.owner}&repo=${repo.name}`)}
                          title="Run AI Code Review"
                          type="button"
                        >
                          <FiSearch size={12} />
                          Review
                        </button>
                        <button
                          className="btn-quick-op"
                          onClick={() => navigate(`/deployments?owner=${repo.owner}&repo=${repo.name}`)}
                          title="Generate Deployment Plan"
                          type="button"
                        >
                          <FiServer size={12} />
                          Deploy
                        </button>
                        <button
                          className="btn-quick-op primary"
                          onClick={() => navigate(`/repo/${repo.owner}/${repo.name}`)}
                          title="Inspect File Tree"
                          type="button"
                        >
                          <FiFolder size={12} />
                          Files
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="empty-repos-state">
                  <FiPackage size={42} className="empty-icon" />
                  <h4>{searchQuery ? 'No repositories match your filter' : 'No repositories imported yet'}</h4>
                  <p>
                    {searchQuery
                      ? 'Try adjusting your search keywords.'
                      : 'Import your GitHub repositories to inspect source code and generate automated CI/CD workflows.'}
                  </p>
                  {!searchQuery && (
                    <button
                      className="btn btn-primary btn-sm"
                      onClick={() => setIsImportModalOpen(true)}
                      type="button"
                    >
                      <FiPlus size={15} />
                      Import Your First Repo
                    </button>
                  )}
                </div>
              )}
            </section>

            {/* GitHub Selector */}
            <div className="selector-wrapper">
              <GithubRepositorySelector
                selectedRepositoryId={selectedRepo?.id}
                onRepositorySelected={handleRepositorySelected}
              />
            </div>
          </div>

          {/* Right Column: Live Telemetry & Activity Feed */}
          <div className="main-right-column">
            {/* Live Activity Feed */}
            <section className="activity-card glass-card">
              <div className="activity-header">
                <div className="activity-title-row">
                  <FiActivity className="accent-icon" />
                  <h3>Live Agent Telemetry</h3>
                </div>
                <div className="activity-tabs">
                  <button
                    className={`activity-tab ${activeActivityTab === 'pipelines' ? 'active' : ''}`}
                    onClick={() => setActiveActivityTab('pipelines')}
                    type="button"
                  >
                    Pipelines ({pipelineRuns.length})
                  </button>
                  <button
                    className={`activity-tab ${activeActivityTab === 'reviews' ? 'active' : ''}`}
                    onClick={() => setActiveActivityTab('reviews')}
                    type="button"
                  >
                    Reviews ({codeReviews.length})
                  </button>
                </div>
              </div>

              <div className="activity-list">
                {activeActivityTab === 'pipelines' && (
                  <>
                    {pipelineRuns.length > 0 ? (
                      pipelineRuns.slice(0, 5).map((run) => (
                        <div key={run.id} className="activity-item">
                          <div className={`activity-status-dot ${run.status}`}></div>
                          <div className="activity-item-body">
                            <div className="activity-item-title-row">
                              <span className="activity-repo-name">
                                {run.owner}/{run.repoName}
                              </span>
                              <span className={`activity-badge ${run.status}`}>
                                {run.status}
                              </span>
                            </div>
                            <div className="activity-item-meta">
                              <span><FiGitBranch size={11} /> {run.branch || 'main'}</span>
                              {run.generationTimeMs && <span>{run.generationTimeMs}ms</span>}
                              {run.completedAt && (
                                <span><FiClock size={11} /> {new Date(run.completedAt).toLocaleTimeString()}</span>
                              )}
                            </div>
                          </div>
                        </div>
                      ))
                    ) : (
                      <div className="empty-activity">
                        <FiGitBranch size={28} />
                        <p>No pipeline runs recorded yet.</p>
                        <button
                          className="btn btn-secondary btn-xs"
                          onClick={() => navigate('/pipelines')}
                          type="button"
                        >
                          Generate Workflow
                        </button>
                      </div>
                    )}
                  </>
                )}

                {activeActivityTab === 'reviews' && (
                  <>
                    {codeReviews.length > 0 ? (
                      codeReviews.slice(0, 5).map((review) => (
                        <div key={review.id} className="activity-item">
                          <div className={`activity-status-dot ${review.verdict || 'approved'}`}></div>
                          <div className="activity-item-body">
                            <div className="activity-item-title-row">
                              <span className="activity-repo-name">
                                {review.owner}/{review.repoName}
                              </span>
                              <span className={`activity-badge verdict-${review.verdict}`}>
                                {review.verdict || 'Reviewed'}
                              </span>
                            </div>
                            <div className="activity-item-meta">
                              <span>{review.filesReviewed || 0} files scanned</span>
                              <span>{review.findingsCount || 0} findings</span>
                              {review.createdAt && (
                                <span><FiClock size={11} /> {new Date(review.createdAt).toLocaleTimeString()}</span>
                              )}
                            </div>
                          </div>
                        </div>
                      ))
                    ) : (
                      <div className="empty-activity">
                        <FiSearch size={28} />
                        <p>No code reviews executed yet.</p>
                        <button
                          className="btn btn-secondary btn-xs"
                          onClick={() => navigate('/reviews')}
                          type="button"
                        >
                          Run Code Review
                        </button>
                      </div>
                    )}
                  </>
                )}
              </div>
            </section>

            {/* System Infrastructure Health Widget */}
            <section className="infra-card glass-card">
              <div className="infra-header">
                <FiTerminal className="accent-icon" />
                <h3>Platform Microservices</h3>
              </div>

              <div className="infra-nodes">
                <div className="infra-node-item">
                  <div className="infra-node-left">
                    <span className="node-indicator online"></span>
                    <div>
                      <span className="node-name">Spring Boot API</span>
                      <span className="node-addr">http://localhost:8080</span>
                    </div>
                  </div>
                  <span className="node-badge">Active</span>
                </div>

                <div className="infra-node-item">
                  <div className="infra-node-left">
                    <span className={`node-indicator ${agentStatus}`}></span>
                    <div>
                      <span className="node-name">Python AI Agents</span>
                      <span className="node-addr">http://localhost:8001</span>
                    </div>
                  </div>
                  <span className={`node-badge ${agentStatus}`}>
                    {agentStatus === 'online' ? 'Online' : 'Offline'}
                  </span>
                </div>

                <div className="infra-node-item">
                  <div className="infra-node-left">
                    <span className="node-indicator online"></span>
                    <div>
                      <span className="node-name">Google Gemini LLM</span>
                      <span className="node-addr">gemini-3.6-flash</span>
                    </div>
                  </div>
                  <span className="node-badge">Connected</span>
                </div>

                <div className="infra-node-item">
                  <div className="infra-node-left">
                    <span className="node-indicator online"></span>
                    <div>
                      <span className="node-name">PostgreSQL Database</span>
                      <span className="node-addr">localhost:5432 / cicddb</span>
                    </div>
                  </div>
                  <span className="node-badge">Active</span>
                </div>
              </div>
            </section>
          </div>
        </div>

        {/* Modal for GitHub Repository Import */}
        <RepoImportModal
          isOpen={isImportModalOpen}
          onClose={() => setIsImportModalOpen(false)}
          importedRepos={importedRepos}
          onRepoImported={handleRepoImported}
        />
      </div>
    </DashboardLayout>
  );
}
