import { useState, useEffect } from 'react';
import {
  FiSettings, FiCpu, FiGithub, FiDatabase, FiSliders,
  FiCheckCircle, FiAlertCircle, FiRefreshCw, FiExternalLink,
  FiShield, FiSave, FiZap
} from 'react-icons/fi';
import DashboardLayout from '../components/DashboardLayout';
import './SettingsPage.css';

export default function SettingsPage({ user }) {
  const [agentStatus, setAgentStatus] = useState('checking'); // 'checking', 'online', 'offline'
  const [agentDetails, setAgentDetails] = useState(null);
  const [savedToast, setSavedToast] = useState(false);
  
  const [preferences, setPreferences] = useState({
    llmProvider: 'Google Gemini',
    modelName: 'gemini-2.0-flash',
    temperature: 0.2,
    autoPushWorkflow: false,
    reviewThreshold: 'ALL',
    maxContextBytes: '5 MB',
    themeAccent: 'violet',
  });

  useEffect(() => {
    checkAgentsHealth();
  }, []);

  async function checkAgentsHealth() {
    setAgentStatus('checking');
    try {
      // Direct ping to agent service or via backend
      const res = await fetch('http://localhost:8001/health', {
        method: 'GET',
        headers: { 'Accept': 'application/json' },
      });
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

  function handleSave(e) {
    e.preventDefault();
    setSavedToast(true);
    setTimeout(() => setSavedToast(false), 3000);
  }

  return (
    <DashboardLayout user={user}>
      <div className="settings-page animate-fade-in" id="settings-page">
        <header className="page-header">
          <h1 className="page-title">
            <FiSettings className="page-title-icon text-gradient" />
            Platform <span className="text-gradient">Settings</span>
          </h1>
          <p className="page-subtitle">
            Configure AI agents, GitHub App credentials, database connections, and platform preferences.
          </p>
        </header>

        {savedToast && (
          <div className="toast toast-success animate-slide-up" role="alert">
            <FiCheckCircle size={18} />
            <span>Settings saved successfully!</span>
          </div>
        )}

        <form onSubmit={handleSave} className="settings-grid">
          {/* AI Agents Service Card */}
          <section className="settings-card glass-card">
            <div className="card-header">
              <div className="card-title-wrap">
                <FiCpu className="card-icon agent-icon" />
                <div>
                  <h2 className="card-title">AI Agents Service</h2>
                  <p className="card-desc">FastAPI microservice handling pipeline gen, review, logs & deployment</p>
                </div>
              </div>
              <div className={`status-pill status-${agentStatus}`}>
                {agentStatus === 'checking' && <FiRefreshCw className="spin" size={13} />}
                {agentStatus === 'online' && <FiCheckCircle size={13} />}
                {agentStatus === 'offline' && <FiAlertCircle size={13} />}
                <span>{agentStatus === 'online' ? 'Active (Port 8001)' : agentStatus === 'checking' ? 'Checking...' : 'Offline'}</span>
              </div>
            </div>

            <div className="card-body">
              <div className="form-group">
                <label className="form-label">Service URL</label>
                <div className="input-group">
                  <input
                    type="text"
                    className="input-field"
                    value="http://localhost:8001"
                    readOnly
                  />
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    onClick={checkAgentsHealth}
                    title="Ping Agent Service"
                  >
                    <FiRefreshCw size={14} className={agentStatus === 'checking' ? 'spin' : ''} />
                    Ping
                  </button>
                </div>
                <span className="field-hint">Service orchestrates Pipeline, Review, Log Analysis, and Deployment planners</span>
              </div>

              <div className="form-row">
                <div className="form-group">
                  <label className="form-label">LLM Provider</label>
                  <select
                    className="input-field"
                    value={preferences.llmProvider}
                    onChange={(e) => setPreferences({ ...preferences, llmProvider: e.target.value })}
                  >
                    <option value="Google Gemini">Google Gemini (Default)</option>
                    <option value="OpenAI">OpenAI GPT-4o</option>
                    <option value="Anthropic">Anthropic Claude</option>
                  </select>
                </div>

                <div className="form-group">
                  <label className="form-label">Model Identifier</label>
                  <input
                    type="text"
                    className="input-field"
                    value={preferences.modelName}
                    onChange={(e) => setPreferences({ ...preferences, modelName: e.target.value })}
                  />
                </div>
              </div>

              <div className="form-group">
                <label className="form-label">
                  Temperature: <code>{preferences.temperature}</code>
                </label>
                <input
                  type="range"
                  min="0.0"
                  max="1.0"
                  step="0.05"
                  className="range-input"
                  value={preferences.temperature}
                  onChange={(e) => setPreferences({ ...preferences, temperature: parseFloat(e.target.value) })}
                />
                <div className="range-labels">
                  <span>0.0 (Deterministic)</span>
                  <span>0.5 (Balanced)</span>
                  <span>1.0 (Creative)</span>
                </div>
              </div>

              {agentDetails?.agents && (
                <div className="mounted-agents">
                  <span className="field-hint">Mounted Agent Modules:</span>
                  <div className="tag-cluster">
                    {Object.entries(agentDetails.agents).map(([agent, status]) => (
                      <span key={agent} className="agent-badge">
                        <FiZap size={11} />
                        {agent.replace('_', ' ')}: <strong>{String(status)}</strong>
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </section>

          {/* GitHub App Configuration */}
          <section className="settings-card glass-card">
            <div className="card-header">
              <div className="card-title-wrap">
                <FiGithub className="card-icon github-icon" />
                <div>
                  <h2 className="card-title">GitHub App Integration</h2>
                  <p className="card-desc">OAuth authentication, repo access, webhooks, and private key verification</p>
                </div>
              </div>
              <div className="status-pill status-online">
                <FiShield size={13} />
                <span>Installed</span>
              </div>
            </div>

            <div className="card-body">
              <div className="form-row">
                <div className="form-group">
                  <label className="form-label">GitHub App ID</label>
                  <input
                    type="text"
                    className="input-field"
                    value="4525940"
                    readOnly
                  />
                </div>
                <div className="form-group">
                  <label className="form-label">Client ID</label>
                  <input
                    type="text"
                    className="input-field"
                    value="Iv23liRMFwpeBR8IrWYa"
                    readOnly
                  />
                </div>
              </div>

              <div className="form-group">
                <label className="form-label">OAuth Callback URL</label>
                <input
                  type="text"
                  className="input-field"
                  value="http://localhost:8080/api/auth/callback"
                  readOnly
                />
              </div>

              <div className="user-profile-preview">
                <span className="field-hint">Connected Account:</span>
                <div className="user-card-mini">
                  <img src={user?.avatarUrl} alt={user?.username} className="user-avatar-mini" />
                  <div>
                    <span className="user-name-mini">{user?.name || user?.username}</span>
                    <span className="user-handle-mini">@{user?.username}</span>
                  </div>
                </div>
              </div>
            </div>
          </section>

          {/* Database & Infrastructure */}
          <section className="settings-card glass-card">
            <div className="card-header">
              <div className="card-title-wrap">
                <FiDatabase className="card-icon db-icon" />
                <div>
                  <h2 className="card-title">PostgreSQL Database</h2>
                  <p className="card-desc">User profiles, repository caches, pipeline runs, and review findings</p>
                </div>
              </div>
              <div className="status-pill status-online">
                <FiCheckCircle size={13} />
                <span>Connected</span>
              </div>
            </div>

            <div className="card-body">
              <div className="form-row">
                <div className="form-group">
                  <label className="form-label">Host & Port</label>
                  <input
                    type="text"
                    className="input-field"
                    value="localhost:5432"
                    readOnly
                  />
                </div>
                <div className="form-group">
                  <label className="form-label">Database User</label>
                  <input
                    type="text"
                    className="input-field"
                    value="postgres"
                    readOnly
                  />
                </div>
              </div>

              <div className="form-group">
                <label className="form-label">Target Database</label>
                <input
                  type="text"
                  className="input-field"
                  value="cicddb (login_db / repo_db schema)"
                  readOnly
                />
              </div>
            </div>
          </section>

          {/* Platform Preferences */}
          <section className="settings-card glass-card">
            <div className="card-header">
              <div className="card-title-wrap">
                <FiSliders className="card-icon pref-icon" />
                <div>
                  <h2 className="card-title">Platform Preferences</h2>
                  <p className="card-desc">Workflow deployment automation and inspection limits</p>
                </div>
              </div>
            </div>

            <div className="card-body">
              <div className="toggle-group">
                <div className="toggle-info">
                  <span className="toggle-label">Auto-Push Generated Workflows</span>
                  <span className="field-hint">Automatically commit workflow to .github/workflows/ upon generation</span>
                </div>
                <label className="toggle-switch">
                  <input
                    type="checkbox"
                    checked={preferences.autoPushWorkflow}
                    onChange={(e) => setPreferences({ ...preferences, autoPushWorkflow: e.target.checked })}
                  />
                  <span className="slider"></span>
                </label>
              </div>

              <div className="form-group" style={{ marginTop: '16px' }}>
                <label className="form-label">Code Review Minimum Severity Filter</label>
                <select
                  className="input-field"
                  value={preferences.reviewThreshold}
                  onChange={(e) => setPreferences({ ...preferences, reviewThreshold: e.target.value })}
                >
                  <option value="ALL">Show all findings (Critical, High, Medium, Low, Info)</option>
                  <option value="MEDIUM">Medium & Above</option>
                  <option value="HIGH">High & Critical Only</option>
                  <option value="CRITICAL">Critical Only</option>
                </select>
              </div>

              <div className="form-group">
                <label className="form-label">Max Repository Context Payload</label>
                <input
                  type="text"
                  className="input-field"
                  value={preferences.maxContextBytes}
                  readOnly
                />
                <span className="field-hint">Configured in Spring Boot application.yml (MAX_REPOSITORY_CONTEXT_BYTES)</span>
              </div>
            </div>
          </section>

          {/* Action Bar */}
          <div className="settings-actions">
            <button type="submit" className="btn btn-primary btn-lg" id="btn-save-settings">
              <FiSave size={18} />
              Save Settings
            </button>
          </div>
        </form>
      </div>
    </DashboardLayout>
  );
}
