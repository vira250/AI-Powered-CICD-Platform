import { useState, useEffect } from 'react';
import {
  FiServer, FiPlay, FiCopy, FiAlertTriangle, FiLayers, FiShield,
  FiActivity, FiRotateCcw, FiCheckCircle, FiCheck, FiClock,
  FiCpu, FiCheckSquare, FiAlertCircle, FiBox
} from 'react-icons/fi';
import {
  getImportedRepos, generateDeploymentPlan, executeDeployment,
  rollbackDeployment, getDeploymentHistory
} from '../api/github';
import DashboardLayout from '../components/DashboardLayout';
import './DeploymentsPage.css';

export default function DeploymentsPage({ user }) {
  const [activeMainTab, setActiveMainTab] = useState('lifecycle'); // 'lifecycle' | 'config'
  const [repos, setRepos] = useState([]);
  const [selectedRepo, setSelectedRepo] = useState(null);

  // Deployment Execution state
  const [deploying, setDeploying] = useState(false);
  const [rollingBack, setRollingBack] = useState(false);
  const [simulateFailure, setSimulateFailure] = useState(false);
  const [deploymentResult, setDeploymentResult] = useState(null);
  const [versionHistory, setVersionHistory] = useState({ current_version: null, previous_versions: [] });

  // Plan generation state
  const [generating, setGenerating] = useState(false);
  const [planResult, setPlanResult] = useState(null);
  const [error, setError] = useState(null);
  const [activeConfigTab, setActiveConfigTab] = useState('dockerfile');
  const [toast, setToast] = useState(null);

  useEffect(() => {
    loadRepos();
  }, []);

  useEffect(() => {
    if (selectedRepo) {
      loadHistory();
    }
  }, [selectedRepo]);

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
    if (!selectedRepo) return;
    try {
      const history = await getDeploymentHistory(selectedRepo.owner, selectedRepo.name);
      if (Array.isArray(history)) {
        // Convert array to current/previous format if needed
        const current = history[0] || null;
        const previous = history.slice(1);
        setVersionHistory({ current_version: current, previous_versions: previous });
      } else if (history && history.current_version !== undefined) {
        setVersionHistory(history);
      }
    } catch {
      // Fallback
    }
  }

  async function handleDeploy() {
    if (!selectedRepo) return;
    setDeploying(true);
    setError(null);
    try {
      const res = await executeDeployment(
        selectedRepo.owner,
        selectedRepo.name,
        'main-' + Math.random().toString(36).substring(2, 8),
        'production',
        'latest',
        simulateFailure
      );
      setDeploymentResult(res);
      setToast({
        message: res.status === 'rolled_back'
          ? 'Deployment failed health check -> Auto-rolled back!'
          : 'Successfully deployed to Production!',
        type: res.status === 'rolled_back' ? 'warning' : 'success',
      });
      loadHistory();
    } catch (err) {
      setError(err.message || 'Deployment execution failed');
    } finally {
      setDeploying(false);
    }
  }

  async function handleRollback(targetVersion = null) {
    if (!selectedRepo) return;
    setRollingBack(true);
    setError(null);
    try {
      const res = await rollbackDeployment(selectedRepo.owner, selectedRepo.name, targetVersion);
      setDeploymentResult(res);
      setToast({ message: `Rolled back to ${res.version || 'previous version'}!`, type: 'info' });
      loadHistory();
    } catch (err) {
      setError(err.message || 'Rollback failed');
    } finally {
      setRollingBack(false);
    }
  }

  async function handleGeneratePlan() {
    if (!selectedRepo) return;
    setGenerating(true);
    setPlanResult(null);
    setError(null);
    try {
      const data = await generateDeploymentPlan(selectedRepo.owner, selectedRepo.name, selectedRepo.defaultBranch);
      setPlanResult(data);
    } catch (err) {
      setError(err.message || 'Deployment planning failed');
    } finally {
      setGenerating(false);
    }
  }

  function copyContent(content) {
    navigator.clipboard.writeText(content);
    setToast({ message: 'Copied to clipboard!', type: 'success' });
    setTimeout(() => setToast(null), 3000);
  }

  return (
    <DashboardLayout user={user}>
      <div className="page-header animate-fade-in">
        <h1 className="page-title">
          <FiServer size={28} /> <span className="text-gradient">Deployment Agent</span>
        </h1>
        <p className="page-subtitle">
          Container deployment lifecycle: Docker Image → Production Environment → Health Check → Rollback & Version History
        </p>
      </div>

      {toast && (
        <div className={`notification-toast ${toast.type}`}>
          {toast.type === 'success' ? <FiCheckCircle /> : <FiAlertCircle />}
          <span>{toast.message}</span>
        </div>
      )}

      {/* Main Tab Navigation */}
      <div className="deploy-tabs-nav animate-fade-in delay-1">
        <button
          className={`deploy-main-tab ${activeMainTab === 'lifecycle' ? 'active' : ''}`}
          onClick={() => setActiveMainTab('lifecycle')}
          type="button"
        >
          <FiActivity size={16} /> Production Lifecycle & Rollback
        </button>
        <button
          className={`deploy-main-tab ${activeMainTab === 'config' ? 'active' : ''}`}
          onClick={() => setActiveMainTab('config')}
          type="button"
        >
          <FiLayers size={16} /> Docker & Deployment Blueprint Generator
        </button>
      </div>

      {/* Repo Selector */}
      <section className="deploy-selector glass-card animate-fade-in delay-2">
        <div className="selector-top-row">
          <h3>Select Target Repository</h3>
          {selectedRepo && (
            <span className="selected-tag">
              Repository: <strong>{selectedRepo.owner}/{selectedRepo.name}</strong>
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
                setDeploymentResult(null);
                setPlanResult(null);
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
      </section>

      {error && (
        <div className="pipeline-error glass-card animate-fade-in">
          <FiAlertTriangle size={20} />
          <span>{error}</span>
        </div>
      )}

      {/* TAB 1: PRODUCTION LIFECYCLE & ROLLBACK */}
      {activeMainTab === 'lifecycle' && selectedRepo && (
        <div className="lifecycle-container animate-fade-in">
          {/* Action Card */}
          <section className="deploy-action-card glass-card">
            <div className="action-card-header">
              <div>
                <h3>Production Deployment Execution</h3>
                <p className="subtext">
                  Triggers the Deployment Agent pipeline: Builds Docker container, executes production rollout, and verifies health endpoint.
                </p>
              </div>

              <div className="simulation-toggle">
                <label className="toggle-label">
                  <input
                    type="checkbox"
                    checked={simulateFailure}
                    onChange={(e) => setSimulateFailure(e.target.checked)}
                  />
                  <span>Simulate Health Check Failure (Test Auto-Rollback)</span>
                </label>
              </div>
            </div>

            <div className="action-buttons-row">
              <button
                className="btn btn-primary btn-lg"
                onClick={handleDeploy}
                disabled={deploying}
              >
                {deploying ? (
                  <>
                    <span className="spinner" style={{ width: 18, height: 18, borderWidth: 2, borderTopColor: 'white' }}></span>
                    Deploying & Verifying Health...
                  </>
                ) : (
                  <>
                    <FiPlay size={18} /> Deploy to Production
                  </>
                )}
              </button>

              <button
                className="btn btn-secondary btn-lg"
                onClick={() => handleRollback()}
                disabled={rollingBack || !versionHistory?.previous_versions?.length}
                title="Rollback to previous stable version"
              >
                <FiRotateCcw size={18} />
                {rollingBack ? 'Rolling Back...' : 'Manual Rollback'}
              </button>
            </div>
          </section>

          {/* Current Version Status */}
          <div className="version-status-grid">
            <div className="status-box glass-card current-version-card">
              <div className="status-box-header">
                <div className="status-title-row">
                  <FiBox size={20} className="text-accent" />
                  <h4>Current Production Version</h4>
                </div>
                {versionHistory?.current_version?.health_check_status && (
                  <span className={`health-pill ${versionHistory.current_version.health_check_status}`}>
                    <FiActivity size={12} /> {versionHistory.current_version.health_check_status.toUpperCase()}
                  </span>
                )}
              </div>

              {versionHistory?.current_version ? (
                <div className="current-version-details">
                  <div className="v-tag">{versionHistory.current_version.version}</div>
                  <div className="v-meta-row">
                    <span><strong>Image:</strong> {versionHistory.current_version.image_tag || 'owner/repo:latest'}</span>
                    <span><strong>Status:</strong> {versionHistory.current_version.status}</span>
                    <span><strong>Endpoint:</strong> {versionHistory.current_version.health_endpoint || '/health'}</span>
                  </div>
                  {versionHistory.current_version.message && (
                    <p className="v-msg">{versionHistory.current_version.message}</p>
                  )}
                </div>
              ) : (
                <div className="empty-version-state">
                  <p>No active version deployed yet. Click "Deploy to Production" to initiate.</p>
                </div>
              )}
            </div>

            {/* Version History List */}
            <div className="status-box glass-card version-history-card">
              <div className="status-box-header">
                <div className="status-title-row">
                  <FiClock size={20} className="text-secondary" />
                  <h4>Version History & Rollback Targets</h4>
                </div>
                <span className="history-count-badge">
                  {versionHistory?.previous_versions?.length || 0} versions archived
                </span>
              </div>

              <div className="previous-versions-list">
                {versionHistory?.previous_versions?.length > 0 ? (
                  versionHistory.previous_versions.map((ver, idx) => (
                    <div key={idx} className="prev-version-item">
                      <div className="prev-ver-left">
                        <span className="ver-badge">{ver.version || `v1.${idx}`}</span>
                        <div className="prev-ver-meta">
                          <span className="prev-img">{ver.image_tag || 'latest'}</span>
                          <span className="prev-date">
                            <FiClock size={11} /> {ver.deployed_at ? new Date(ver.deployed_at).toLocaleTimeString() : 'Recent'}
                          </span>
                        </div>
                      </div>

                      <button
                        className="btn btn-secondary btn-xs"
                        onClick={() => handleRollback(ver.version)}
                        disabled={rollingBack}
                        type="button"
                      >
                        <FiRotateCcw size={12} /> Restore
                      </button>
                    </div>
                  ))
                ) : (
                  <p className="empty-hint">Deploy new versions to populate history and enable rollback.</p>
                )}
              </div>
            </div>
          </div>
        </div>
      )}

      {/* TAB 2: DOCKER & BLUEPRINT GENERATOR */}
      {activeMainTab === 'config' && selectedRepo && (
        <div className="config-container animate-fade-in">
          <div className="action-row glass-card" style={{ padding: '20px', marginBottom: '20px' }}>
            <button
              className="btn btn-primary btn-lg"
              onClick={handleGeneratePlan}
              disabled={generating}
              id="btn-generate-plan"
            >
              {generating ? (
                <>
                  <span className="spinner" style={{ width: 18, height: 18, borderWidth: 2, borderTopColor: 'white' }}></span>
                  Analyzing Architecture & Generating Blueprint...
                </>
              ) : (
                <>
                  <FiPlay size={18} /> Generate Dockerfile & Deploy Config
                </>
              )}
            </button>
          </div>

          {planResult && (
            <section className="deploy-result animate-fade-in">
              <div className="deploy-meta glass-card">
                <div className="meta-item">
                  <span className="meta-label">Deployment Strategy</span>
                  <span className="meta-value badge badge-strategy">
                    <FiLayers size={14} /> {planResult.deployment_strategy}
                  </span>
                </div>
                <div className="meta-item">
                  <span className="meta-label">Rollback Mechanism</span>
                  <span className="meta-value badge badge-rollback">
                    <FiShield size={14} /> {planResult.rollback?.strategy || 'automated'}
                  </span>
                </div>
                <div className="meta-item">
                  <span className="meta-label">Health Check</span>
                  <span className="meta-value badge badge-health">
                    <FiActivity size={14} /> {planResult.environments?.[0]?.health_check?.endpoint || '/health'}
                  </span>
                </div>
              </div>

              <div className="deploy-config-tabs">
                <button
                  className={`tab-btn ${activeConfigTab === 'dockerfile' ? 'active' : ''}`}
                  onClick={() => setActiveConfigTab('dockerfile')}
                  type="button"
                >
                  Dockerfile
                </button>
                <button
                  className={`tab-btn ${activeConfigTab === 'compose' ? 'active' : ''}`}
                  onClick={() => setActiveConfigTab('compose')}
                  type="button"
                >
                  docker-compose.yml
                </button>
              </div>

              <div className="code-viewer glass-card">
                <div className="code-viewer-header">
                  <span className="code-filename">
                    {activeConfigTab === 'dockerfile' ? 'Dockerfile' : 'docker-compose.yml'}
                  </span>
                  <button
                    className="btn btn-secondary btn-sm"
                    onClick={() => copyContent(activeConfigTab === 'dockerfile' ? planResult.dockerfile : planResult.docker_compose)}
                    type="button"
                  >
                    <FiCopy size={14} /> Copy
                  </button>
                </div>
                <pre className="code-block">
                  <code>{activeConfigTab === 'dockerfile' ? planResult.dockerfile : planResult.docker_compose}</code>
                </pre>
              </div>
            </section>
          )}
        </div>
      )}
    </DashboardLayout>
  );
}
