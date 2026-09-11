import { useState } from 'react';
import {
  FiFileText, FiPlay, FiAlertTriangle, FiTarget, FiTool,
  FiActivity, FiZap, FiCopy, FiCheckCircle, FiCheck, FiCode
} from 'react-icons/fi';
import { analyzeLogs, remediatePipeline } from '../api/github';
import DashboardLayout from '../components/DashboardLayout';
import './LogAnalysisPage.css';

const SAMPLE_LOG = `> npm run build
> project@1.0.0 build
> tsc && vite build

src/components/App.tsx:42:5 - error TS2322: Type 'string' is not assignable to type 'number'.
  42     const count: number = "hello";
         ~~~~~

src/utils/helpers.ts:15:3 - error TS2345: Argument of type 'null' is not assignable to parameter of type 'string'.
  15   processData(null);
       ~~~~~~~~~~~

Found 2 errors in 2 files.
npm ERR! code ELIFECYCLE
npm ERR! errno 1
npm ERR! project@1.0.0 build: \`tsc && vite build\`
npm ERR! Exit status 1`;

export default function LogAnalysisPage({ user }) {
  const [logText, setLogText] = useState('');
  const [analyzing, setAnalyzing] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  // Self-healing state
  const [remediating, setRemediating] = useState(false);
  const [healedResult, setHealedResult] = useState(null);
  const [copiedYaml, setCopiedYaml] = useState(false);

  async function handleAnalyze() {
    if (!logText.trim()) return;
    setAnalyzing(true);
    setResult(null);
    setHealedResult(null);
    setError(null);
    try {
      const data = await analyzeLogs(logText);
      setResult(data);
    } catch (err) {
      setError(err.message || 'Log analysis failed');
    } finally {
      setAnalyzing(false);
    }
  }

  async function handleSelfHeal() {
    if (!logText.trim()) return;
    setRemediating(true);
    setError(null);
    try {
      const sampleYaml = "name: CI\non: [push]\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: actions/checkout@v4\n      - run: npm install\n      - run: npm run build\n";
      const data = await remediatePipeline(
        'project',
        'app',
        'main',
        sampleYaml,
        logText
      );
      setHealedResult(data);
    } catch (err) {
      setError(err.message || 'Self-healing remediation failed');
    } finally {
      setRemediating(false);
    }
  }

  function loadSample() {
    setLogText(SAMPLE_LOG);
  }

  function handleCopyYaml() {
    if (healedResult?.yaml_content) {
      navigator.clipboard.writeText(healedResult.yaml_content);
      setCopiedYaml(true);
      setTimeout(() => setCopiedYaml(false), 2000);
    }
  }

  const confidencePercent = result ? Math.round((result.confidence_score || 0) * 100) : 0;

  return (
    <DashboardLayout user={user}>
      <div className="page-header animate-fade-in">
        <h1 className="page-title">
          <FiFileText size={28} /> <span className="text-gradient">Log Analysis Agent</span>
        </h1>
        <p className="page-subtitle">
          3-Step Pipeline Failure Diagnosis: Analyse Logs → Find Root Cause → Suggest Fix (with Multi-Agent Self-Healing)
        </p>
      </div>

      {/* Log Input */}
      <section className="log-input glass-card animate-fade-in delay-1">
        <div className="log-input-header">
          <h3>CI/CD Pipeline Failure Logs</h3>
          <button className="btn btn-ghost btn-sm" onClick={loadSample} type="button">
            Load Sample Error Logs
          </button>
        </div>
        <textarea
          className="input log-textarea"
          placeholder="Paste your CI/CD pipeline failure logs or terminal stack traces here..."
          value={logText}
          onChange={(e) => setLogText(e.target.value)}
        />
        <div className="log-action-buttons">
          <button
            className="btn btn-primary btn-lg"
            onClick={handleAnalyze}
            disabled={analyzing || !logText.trim()}
            id="btn-analyze-logs"
          >
            {analyzing ? (
              <>
                <span className="spinner" style={{ width: 18, height: 18, borderWidth: 2, borderTopColor: 'white' }}></span>
                Diagnosing Pipeline Failure...
              </>
            ) : (
              <>
                <FiPlay size={18} /> Analyze Logs
              </>
            )}
          </button>
        </div>
      </section>

      {error && (
        <div className="pipeline-error glass-card animate-fade-in">
          <FiAlertTriangle size={20} />
          <span>{error}</span>
        </div>
      )}

      {result && (
        <section className="log-result animate-fade-in">
          {/* Executive Overview */}
          <div className="log-overview glass-card">
            <div className="log-status-row">
              <div className={`log-status-badge ${result.status || 'failed'}`}>
                <FiAlertTriangle size={18} />
                <span>{(result.status || 'FAILED').toUpperCase()}</span>
              </div>
              <span className="log-time">{result.generation_time_ms}ms</span>
            </div>

            <p className="log-summary-text">{result.log_summary}</p>

            <div className="log-stats">
              <span className="stat-chip"><strong>{result.error_count || result.errors?.length || 0}</strong> errors detected</span>
              <span className="stat-chip"><strong>{confidencePercent}%</strong> diagnosis confidence</span>
              <span className="stat-chip">Impact: <strong>{result.impact?.severity?.toUpperCase() || 'HIGH'}</strong></span>
            </div>
          </div>

          {/* MULTI-AGENT SELF-HEALING BANNER */}
          <div className="self-healing-banner glass-card">
            <div className="sh-left">
              <div className="sh-icon"><FiZap size={24} /></div>
              <div>
                <h4>Multi-Agent Self-Healing Pipeline</h4>
                <p>Pass this diagnostic result directly to the Pipeline Generation Agent to synthesize a corrected workflow YAML.</p>
              </div>
            </div>
            <button
              className="btn btn-accent btn-lg"
              onClick={handleSelfHeal}
              disabled={remediating}
            >
              {remediating ? (
                <>
                  <span className="spinner" style={{ width: 16, height: 16, borderWidth: 2, borderTopColor: 'white' }}></span>
                  Regenerating Fixed Workflow...
                </>
              ) : (
                <>
                  <FiZap size={18} /> Self-Heal Pipeline
                </>
              )}
            </button>
          </div>

          {/* Healed YAML Result */}
          {healedResult && (
            <div className="healed-result-card glass-card animate-fade-in">
              <div className="healed-header">
                <div className="healed-title-row">
                  <FiCheckCircle size={20} className="text-success" />
                  <h4>Self-Healed GitHub Actions YAML</h4>
                </div>
                <button className="btn btn-secondary btn-sm" onClick={handleCopyYaml} type="button">
                  {copiedYaml ? <FiCheck size={14} /> : <FiCopy size={14} />}
                  {copiedYaml ? 'Copied YAML!' : 'Copy Healed YAML'}
                </button>
              </div>
              <pre className="healed-code-box">
                <code>{healedResult.yaml_content}</code>
              </pre>
            </div>
          )}

          {/* Root Causes */}
          {result.root_causes?.length > 0 && (
            <div className="log-root-causes glass-card">
              <h3><FiTarget size={18} /> Root Causes</h3>
              <div className="root-cause-list">
                {result.root_causes.map((rc, i) => (
                  <div key={i} className="root-cause-item">
                    <div className="rc-header">
                      <span className="rc-category">{rc.category}</span>
                      <span className="rc-confidence">{Math.round((rc.confidence || 0) * 100)}% confidence</span>
                    </div>
                    <p>{rc.description}</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Suggested Fixes */}
          {result.suggested_fixes?.length > 0 && (
            <div className="log-fixes glass-card">
              <h3><FiTool size={18} /> Suggested Fixes</h3>
              <div className="fixes-list">
                {result.suggested_fixes.map((fix, i) => (
                  <div key={i} className={`fix-card priority-${fix.priority}`}>
                    <div className="fix-header">
                      <span className={`fix-priority ${fix.priority}`}>{fix.priority}</span>
                      <span className="fix-title">{fix.title}</span>
                    </div>
                    <p className="fix-description">{fix.description}</p>
                    {fix.code_snippet && (
                      <pre className="fix-code">
                        <code>{fix.code_snippet}</code>
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>
      )}
    </DashboardLayout>
  );
}
