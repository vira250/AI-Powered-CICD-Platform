import { useState, useEffect, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  FiArrowLeft, FiGitBranch, FiStar, FiGlobe, FiLock,
  FiExternalLink, FiUploadCloud, FiCheck, FiCopy, FiCode,
  FiClock, FiGitCommit, FiFolder, FiFileText,
  FiSearch, FiEye, FiImage
} from 'react-icons/fi';
import { getRepoDetails, getRepoStructure, importRepo, pushFile, getRepoContext } from '../api/github';
import Navbar from '../components/Navbar';
import FileViewerModal from '../components/FileViewerModal';
import './RepoDetailPage.css';

function getFileIcon(filename) {
  const ext = (filename || '').split('.').pop().toLowerCase();
  if (['js', 'jsx', 'ts', 'tsx', 'py', 'java', 'c', 'cpp', 'cs', 'go', 'rs', 'php', 'rb', 'html', 'css', 'json', 'yml', 'yaml', 'xml', 'md'].includes(ext)) {
    return <FiCode className="tree-icon icon-code" size={16} />;
  }
  if (['png', 'jpg', 'jpeg', 'gif', 'svg', 'webp', 'ico'].includes(ext)) {
    return <FiImage className="tree-icon icon-img" size={16} />;
  }
  return <FiFileText className="tree-icon icon-file" size={16} />;
}

function RepoTreeNode({ node, depth = 0, onFileClick, searchFilter = '' }) {
  const [isOpen, setIsOpen] = useState(depth < 2);

  if (!node) {
    return null;
  }

  if (node.type === 'dir') {
    const children = node.children || [];
    const hasChildren = children.length > 0;

    return (
      <div className="tree-folder-group">
        <div
          className="tree-folder-header"
          onClick={() => setIsOpen(!isOpen)}
          style={{ paddingLeft: `${depth * 16 + 12}px` }}
          role="button"
          tabIndex={0}
        >
          <span className="tree-folder-toggle-icon">{isOpen ? '▾' : '▸'}</span>
          <FiFolder className={`tree-icon icon-folder ${isOpen ? 'is-open' : ''}`} size={16} />
          <span className="tree-folder-name">{node.name || 'root'}</span>
          <span className="tree-folder-count">{children.length} items</span>
        </div>

        {isOpen && hasChildren && (
          <div className="tree-folder-children">
            {children.map((child) => (
              <RepoTreeNode
                key={child.path || child.name}
                node={child}
                depth={depth + 1}
                onFileClick={onFileClick}
                searchFilter={searchFilter}
              />
            ))}
          </div>
        )}
      </div>
    );
  }

  // Filter check if searchFilter is provided
  if (searchFilter && !node.name.toLowerCase().includes(searchFilter.toLowerCase()) && !(node.path || '').toLowerCase().includes(searchFilter.toLowerCase())) {
    return null;
  }

  return (
    <div
      className="tree-file-row"
      style={{ paddingLeft: `${depth * 16 + 28}px` }}
      onClick={() => onFileClick?.(node.path || node.name)}
      role="button"
      tabIndex={0}
      title="Click to view file content"
    >
      {getFileIcon(node.name)}
      <span className="tree-file-name">{node.name}</span>
      {node.size != null ? <span className="tree-file-size">{(node.size / 1024).toFixed(1)} KB</span> : null}
      <span className="tree-file-view-badge">
        <FiEye size={12} /> View
      </span>
    </div>
  );
}

export default function RepoDetailPage({ user }) {
  const { owner, name } = useParams();
  const navigate = useNavigate();

  const [selectedFilePath, setSelectedFilePath] = useState(null);
  const [isFileModalOpen, setIsFileModalOpen] = useState(false);
  const [treeSearch, setTreeSearch] = useState('');

  const [repo, setRepo] = useState(null);
  const [loading, setLoading] = useState(true);
  const [importing, setImporting] = useState(false);
  const [toast, setToast] = useState(null);
  const [structure, setStructure] = useState(null);
  const [structureLoading, setStructureLoading] = useState(true);
  const [structureError, setStructureError] = useState(null);

  // Push form state
  const [pushForm, setPushForm] = useState({
    path: '',
    content: '',
    message: '',
    branch: '',
  });
  const [pushing, setPushing] = useState(false);
  const [pushResult, setPushResult] = useState(null);
  const [pushHistory, setPushHistory] = useState([]);

  // Repository Context state
  const [contextData, setContextData] = useState(null);
  const [contextLoading, setContextLoading] = useState(false);
  const [isContextModalOpen, setIsContextModalOpen] = useState(false);

  async function handleLoadContext() {
    setContextLoading(true);
    setContextData(null);
    setIsContextModalOpen(true);
    try {
      const data = await getRepoContext(owner, name, repo?.default_branch);
      setContextData(data);
      showToast('Repository context generated successfully!', 'success');
    } catch (err) {
      showToast(err.message || 'Failed to load repository context', 'error');
    } finally {
      setContextLoading(false);
    }
  }

  useEffect(() => {
    fetchRepo();
  }, [owner, name]);

  async function fetchRepo() {
    setLoading(true);
    setStructureLoading(true);
    setStructureError(null);
    try {
      const data = await getRepoDetails(owner, name);
      setRepo(data);
      setPushForm(prev => ({ ...prev, branch: data.default_branch || 'main' }));

      const tree = await getRepoStructure(owner, name, data.default_branch);
      setStructure(tree);
    } catch (err) {
      if (!repo) {
        showToast('Failed to load repository', 'error');
      }
      setStructureError('Failed to load repository structure');
    } finally {
      setLoading(false);
      setStructureLoading(false);
    }
  }

  async function handleImport() {
    setImporting(true);
    try {
      await importRepo(repo);
      setRepo(prev => ({ ...prev, imported: true }));
      showToast('Repository imported successfully!', 'success');
    } catch (err) {
      showToast(err.message || 'Failed to import', 'error');
    } finally {
      setImporting(false);
    }
  }

  async function handlePush(e) {
    e.preventDefault();
    if (!pushForm.path || !pushForm.content) {
      showToast('Please fill in file path and content', 'error');
      return;
    }

    setPushing(true);
    setPushResult(null);
    try {
      const result = await pushFile({
        owner,
        repo: name,
        path: pushForm.path,
        content: pushForm.content,
        message: pushForm.message || `Update ${pushForm.path}`,
        branch: pushForm.branch || undefined,
      });

      setPushResult(result);
      setPushHistory(prev => [
        {
          path: pushForm.path,
          message: pushForm.message || `Update ${pushForm.path}`,
          sha: result.commit_sha?.substring(0, 7),
          time: new Date().toLocaleTimeString(),
        },
        ...prev,
      ]);
      showToast('File pushed successfully!', 'success');
      // Clear content after successful push
      setPushForm(prev => ({ ...prev, content: '', message: '' }));
    } catch (err) {
      showToast(err.message || 'Push failed', 'error');
    } finally {
      setPushing(false);
    }
  }

  function showToast(message, type) {
    setToast({ message, type });
    setTimeout(() => setToast(null), 4000);
  }

  function copyCloneUrl() {
    navigator.clipboard.writeText(repo.clone_url);
    showToast('Clone URL copied!', 'success');
  }

  function handleFileClick(path) {
    setSelectedFilePath(path);
    setIsFileModalOpen(true);
  }

  function handleEditFileFromViewer({ path, content }) {
    setPushForm((prev) => ({
      ...prev,
      path: path || prev.path,
      content: content || prev.content,
    }));
    document.getElementById('push-form')?.scrollIntoView({ behavior: 'smooth' });
  }

  if (loading) {
    return (
      <div className="repo-detail-page">
        <Navbar user={user} />
        <main className="page">
          <div className="container">
            <div className="loading-container">
              <div className="spinner spinner-lg"></div>
              <p style={{ color: 'var(--text-secondary)' }}>Loading repository...</p>
            </div>
          </div>
        </main>
      </div>
    );
  }

  if (!repo) {
    return (
      <div className="repo-detail-page">
        <Navbar user={user} />
        <main className="page">
          <div className="container">
            <div className="empty-state">
              <h3>Repository not found</h3>
              <button className="btn btn-primary" onClick={() => navigate('/dashboard')}>
                Back to Dashboard
              </button>
            </div>
          </div>
        </main>
      </div>
    );
  }

  return (
    <div className="repo-detail-page" id="repo-detail-page">
      <Navbar user={user} />

      <main className="page">
        <div className="container">
          {/* Back button */}
          <button
            className="btn btn-ghost back-btn animate-fade-in"
            onClick={() => navigate('/dashboard')}
            id="btn-back"
          >
            <FiArrowLeft size={18} />
            Back to Dashboard
          </button>

          {/* Repo Header */}
          <div className="repo-detail-header glass-card animate-fade-in delay-1">
            <div className="repo-detail-top">
              <div className="repo-detail-info">
                <div className="repo-detail-title-row">
                  <h1 className="repo-detail-name">
                    <span className="repo-detail-owner">{owner} /</span>
                    {name}
                  </h1>
                  <span className={`badge ${repo.private ? 'badge-private' : 'badge-public'}`}>
                    {repo.private ? <><FiLock size={10} /> Private</> : <><FiGlobe size={10} /> Public</>}
                  </span>
                  {repo.imported && (
                    <span className="badge badge-imported">
                      <FiCheck size={10} /> Imported
                    </span>
                  )}
                </div>
                {repo.description && (
                  <p className="repo-detail-desc">{repo.description}</p>
                )}
              </div>

              <div className="repo-detail-actions">
                {!repo.imported && (
                  <button
                    className="btn btn-primary"
                    onClick={handleImport}
                    disabled={importing}
                    id="btn-import-repo"
                  >
                    {importing ? (
                      <span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }}></span>
                    ) : (
                      <>Import Project</>
                    )}
                  </button>
                )}
                <button
                  className="btn btn-secondary"
                  onClick={handleLoadContext}
                  id="btn-inspect-context"
                  style={{ display: 'inline-flex', alignItems: 'center', gap: '8px' }}
                >
                  <FiCode size={16} />
                  Inspect Agent Context
                </button>
                <a
                  href={repo.html_url}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="btn btn-secondary"
                  id="btn-view-github"
                >
                  <FiExternalLink size={16} />
                  View on GitHub
                </a>
              </div>
            </div>

            {/* Repo Stats */}
            <div className="repo-detail-stats">
              <div className="stat-item">
                <FiStar size={16} />
                <span>{repo.stargazers_count} stars</span>
              </div>
              <div className="stat-item">
                <FiGitBranch size={16} />
                <span>{repo.default_branch}</span>
              </div>
              {repo.language && (
                <div className="stat-item">
                  <FiCode size={16} />
                  <span>{repo.language}</span>
                </div>
              )}
              <div className="stat-item">
                <FiClock size={16} />
                <span>Updated {new Date(repo.updated_at).toLocaleDateString()}</span>
              </div>
            </div>

            {/* Clone URL */}
            <div className="clone-url-row">
              <code className="clone-url">{repo.clone_url}</code>
              <button className="btn btn-ghost btn-sm" onClick={copyCloneUrl} id="btn-copy-clone">
                <FiCopy size={14} />
              </button>
            </div>
          </div>

          {/* Repository Structure */}
          <section className="structure-section glass-card animate-fade-in delay-2">
            <div className="structure-header">
              <div>
                <h2 className="structure-title">Repository Structure & Files</h2>
                <p className="structure-subtitle">Click any file to view its source code and inspect details.</p>
              </div>
              {!repo.imported && (
                <button
                  className="btn btn-primary"
                  onClick={handleImport}
                  disabled={importing}
                  id="btn-import-repo-structure"
                >
                  {importing ? (
                    <span className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }}></span>
                  ) : (
                    <>Import Project</>
                  )}
                </button>
              )}
            </div>

            {structureLoading ? (
              <div className="structure-loading">
                <div className="spinner"></div>
                <p>Analyzing folders and files...</p>
              </div>
            ) : structureError ? (
              <div className="empty-state structure-error">
                <h3>{structureError}</h3>
                <p>Try refreshing the page or importing the repo directly.</p>
              </div>
            ) : structure ? (
              <>
                <div className="structure-summary">
                  <div className="structure-summary-item">
                    <span className="structure-summary-value">{structure.folderCount}</span>
                    <span className="structure-summary-label">Folders</span>
                  </div>
                  <div className="structure-summary-item">
                    <span className="structure-summary-value">{structure.fileCount}</span>
                    <span className="structure-summary-label">Files</span>
                  </div>
                  <div className="structure-summary-item">
                    <span className="structure-summary-value">{structure.branch}</span>
                    <span className="structure-summary-label">Branch</span>
                  </div>
                </div>

                <div className="tree-search-bar">
                  <FiSearch size={16} className="search-icon" />
                  <input
                    type="search"
                    className="input tree-search-input"
                    placeholder="Filter files by name or path..."
                    value={treeSearch}
                    onChange={(e) => setTreeSearch(e.target.value)}
                    id="input-filter-tree-files"
                  />
                </div>

                <div className="structure-tree">
                  <RepoTreeNode
                    node={structure.tree}
                    onFileClick={handleFileClick}
                    searchFilter={treeSearch}
                  />
                </div>
              </>
            ) : null}
          </section>

          {/* Push to GitHub Section */}
          <div className="push-section animate-fade-in delay-2">
            <div className="push-form-card glass-card">
              <div className="push-form-header">
                <FiUploadCloud size={22} className="push-icon" />
                <div>
                  <h2>Push to GitHub</h2>
                  <p>Create or update a file in this repository</p>
                </div>
              </div>

              <form onSubmit={handlePush} className="push-form" id="push-form">
                <div className="push-form-row">
                  <div className="form-group" style={{ flex: 2 }}>
                    <label className="form-label">File Path</label>
                    <input
                      type="text"
                      className="input"
                      placeholder="e.g. src/index.js"
                      value={pushForm.path}
                      onChange={e => setPushForm(prev => ({ ...prev, path: e.target.value }))}
                      id="input-file-path"
                    />
                  </div>
                  <div className="form-group" style={{ flex: 1 }}>
                    <label className="form-label">Branch</label>
                    <input
                      type="text"
                      className="input"
                      placeholder="main"
                      value={pushForm.branch}
                      onChange={e => setPushForm(prev => ({ ...prev, branch: e.target.value }))}
                      id="input-branch"
                    />
                  </div>
                </div>

                <div className="form-group">
                  <label className="form-label">File Content</label>
                  <textarea
                    className="input"
                    placeholder="Enter file content here..."
                    value={pushForm.content}
                    onChange={e => setPushForm(prev => ({ ...prev, content: e.target.value }))}
                    id="input-content"
                  />
                </div>

                <div className="form-group">
                  <label className="form-label">Commit Message</label>
                  <input
                    type="text"
                    className="input"
                    placeholder={`Update ${pushForm.path || 'file'}`}
                    value={pushForm.message}
                    onChange={e => setPushForm(prev => ({ ...prev, message: e.target.value }))}
                    id="input-commit-message"
                  />
                </div>

                <button
                  type="submit"
                  className="btn btn-success btn-lg"
                  disabled={pushing || !pushForm.path || !pushForm.content}
                  id="btn-push"
                >
                  {pushing ? (
                    <>
                      <span className="spinner" style={{ width: 18, height: 18, borderWidth: 2, borderTopColor: 'white' }}></span>
                      Pushing...
                    </>
                  ) : (
                    <>
                      <FiUploadCloud size={20} />
                      Push to GitHub
                    </>
                  )}
                </button>
              </form>

              {/* Push result */}
              {pushResult && (
                <div className="push-result">
                  <div className="push-result-header">
                    <FiCheck size={18} className="push-result-icon" />
                    <span>File pushed successfully!</span>
                  </div>
                  {pushResult.html_url && (
                    <a
                      href={pushResult.html_url}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="push-result-link"
                    >
                      <FiExternalLink size={14} />
                      View file on GitHub
                    </a>
                  )}
                </div>
              )}
            </div>

            {/* Push History */}
            {pushHistory.length > 0 && (
              <div className="push-history glass-card">
                <h3 className="push-history-title">
                  <FiGitCommit size={18} />
                  Recent Pushes
                </h3>
                <div className="push-history-list">
                  {pushHistory.map((entry, i) => (
                    <div key={i} className="push-history-item">
                      <div className="push-history-dot"></div>
                      <div className="push-history-content">
                        <code className="push-history-path">{entry.path}</code>
                        <span className="push-history-meta">
                          {entry.sha && <span className="push-history-sha">{entry.sha}</span>}
                          <span>{entry.time}</span>
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </main>

      {toast && (
        <div className={`toast toast-${toast.type}`} id="toast-notification">
          {toast.message}
        </div>
      )}

      <FileViewerModal
        isOpen={isFileModalOpen}
        onClose={() => setIsFileModalOpen(false)}
        owner={owner}
        repo={name}
        path={selectedFilePath}
        branch={repo?.default_branch || 'main'}
        onEditFile={handleEditFileFromViewer}
      />

      {isContextModalOpen && (
        <div className="modal-overlay" onClick={() => setIsContextModalOpen(false)}>
          <div className="modal-content glass-card context-modal" onClick={e => e.stopPropagation()} style={{ maxWidth: '850px', width: '90%' }}>
            <div className="modal-header">
              <h3 className="modal-title">Pipeline Agent Repository Context</h3>
              <button className="btn-close" onClick={() => setIsContextModalOpen(false)} style={{ background: 'none', border: 'none', color: 'var(--text-primary)', fontSize: '24px', cursor: 'pointer' }}>×</button>
            </div>
            <div className="modal-body" style={{ maxHeight: '70vh', overflowY: 'auto', textAlign: 'left', padding: '20px 0' }}>
              {contextLoading ? (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '16px', padding: '40px 0' }}>
                  <div className="spinner spinner-lg"></div>
                  <p style={{ color: 'var(--text-secondary)' }}>Assembling full repository context from GitHub...</p>
                </div>
              ) : contextData ? (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                  <div className="stats-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '12px' }}>
                    <div className="glass-card stat-card" style={{ padding: '12px' }}>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Commit SHA</span>
                      <code style={{ display: 'block', fontSize: '13px', marginTop: '4px', fontWeight: 'bold' }}>{contextData.repository.commitSha.substring(0, 7)}</code>
                    </div>
                    <div className="glass-card stat-card" style={{ padding: '12px' }}>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Total Nodes</span>
                      <div style={{ fontSize: '18px', fontWeight: 'bold', marginTop: '4px' }}>{contextData.structure.length}</div>
                    </div>
                    <div className="glass-card stat-card" style={{ padding: '12px' }}>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Source Files</span>
                      <div style={{ fontSize: '18px', fontWeight: 'bold', marginTop: '4px' }}>{contextData.files.length}</div>
                    </div>
                    <div className="glass-card stat-card" style={{ padding: '12px' }}>
                      <span style={{ fontSize: '12px', color: 'var(--text-secondary)' }}>Text Size</span>
                      <div style={{ fontSize: '18px', fontWeight: 'bold', marginTop: '4px' }}>{(contextData.totalBytes / 1024).toFixed(1)} KB</div>
                    </div>
                  </div>

                  {contextData.truncated && (
                    <div className="alert alert-warning" style={{ backgroundColor: 'rgba(245, 158, 11, 0.1)', border: '1px solid var(--warning)', padding: '12px', borderRadius: '8px', color: 'var(--warning)', fontSize: '14px' }}>
                      <strong>Payload Truncated:</strong> {contextData.message}
                    </div>
                  )}

                  <div>
                    <h4 style={{ marginBottom: '8px' }}>Context Payload Structure (Sample)</h4>
                    <pre style={{ backgroundColor: 'rgba(0,0,0,0.3)', padding: '16px', borderRadius: '8px', overflowX: 'auto', fontSize: '12px', color: '#a3e635', fontFamily: 'monospace' }}>
                      {JSON.stringify({
                        repository: contextData.repository,
                        totalBytes: contextData.totalBytes,
                        truncated: contextData.truncated,
                        structureSample: contextData.structure.slice(0, 5).concat([{ path: '...', type: '...' }]),
                        filesSample: contextData.files.slice(0, 3).map(f => ({
                          path: f.path,
                          type: f.type,
                          size: f.size,
                          isSecret: f.isSecret,
                          content: f.content ? (f.content.length > 80 ? f.content.substring(0, 80) + '...' : f.content) : null
                        }))
                      }, null, 2)}
                    </pre>
                  </div>
                </div>
              ) : (
                <p>No context data loaded.</p>
              )}
            </div>
            <div className="modal-footer" style={{ display: 'flex', justifyContent: 'flex-end', gap: '12px', marginTop: '20px' }}>
              <button className="btn btn-secondary" onClick={() => setIsContextModalOpen(false)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
