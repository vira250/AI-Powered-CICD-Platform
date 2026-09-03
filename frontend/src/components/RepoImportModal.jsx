import { useState, useEffect, useMemo } from 'react';
import { FiX, FiSearch, FiGlobe, FiLock, FiCheck, FiPlus, FiRefreshCw, FiBox } from 'react-icons/fi';
import { getGithubRepos, importRepo } from '../api/github';
import './RepoImportModal.css';

export default function RepoImportModal({ isOpen, onClose, importedRepos = [], onRepoImported }) {
  const [repositories, setRepositories] = useState([]);
  const [loading, setLoading] = useState(false);
  const [importingId, setImportingId] = useState(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState(null);
  const [importedMap, setImportedMap] = useState({});

  useEffect(() => {
    if (isOpen) {
      loadRepos();
    }
  }, [isOpen]);

  useEffect(() => {
    const map = {};
    (importedRepos || []).forEach((item) => {
      const key = item.githubRepoId || item.id;
      if (key) map[key] = true;
      if (item.fullName || item.full_name) map[item.fullName || item.full_name] = true;
    });
    setImportedMap(map);
  }, [importedRepos]);

  async function loadRepos() {
    setLoading(true);
    setError(null);
    try {
      const data = await getGithubRepos();
      setRepositories(Array.isArray(data) ? data : []);
    } catch (err) {
      setError(err.message || 'Failed to load GitHub repositories');
      setRepositories([]);
    } finally {
      setLoading(false);
    }
  }

  async function handleImport(repo) {
    setImportingId(repo.id);
    try {
      const result = await importRepo(repo);
      setImportedMap((prev) => ({
        ...prev,
        [repo.id]: true,
        [repo.full_name || repo.fullName]: true,
      }));
      onRepoImported?.(result);
    } catch (err) {
      alert(err.message || 'Failed to import repository');
    } finally {
      setImportingId(null);
    }
  }

  const filteredRepos = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return repositories;
    return repositories.filter((r) => {
      const name = (r.name || '').toLowerCase();
      const fullName = (r.full_name || r.fullName || '').toLowerCase();
      const owner = (r.owner?.login || r.owner || '').toLowerCase();
      const desc = (r.description || '').toLowerCase();
      return name.includes(q) || fullName.includes(q) || owner.includes(q) || desc.includes(q);
    });
  }, [repositories, search]);

  if (!isOpen) return null;

  return (
    <div className="modal-overlay" onClick={onClose} id="modal-repo-import-overlay">
      <div
        className="modal-container repo-import-modal glass-card"
        onClick={(e) => e.stopPropagation()}
        id="modal-repo-import-content"
      >
        <div className="modal-header">
          <div className="modal-title-box">
            <FiBox size={22} className="modal-title-icon" />
            <div>
              <h2 className="modal-title">Import GitHub Repository</h2>
              <p className="modal-subtitle">Select a repository to import into your CI/CD platform.</p>
            </div>
          </div>
          <button className="modal-close-btn" onClick={onClose} type="button" id="btn-close-repo-modal">
            <FiX size={20} />
          </button>
        </div>

        <div className="modal-search-bar">
          <FiSearch size={18} className="search-icon" />
          <input
            type="search"
            className="input search-input"
            placeholder="Search repositories by name, owner, or description..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            id="input-search-modal-repos"
          />
          <button className="btn btn-secondary btn-sm" onClick={loadRepos} type="button" disabled={loading}>
            <FiRefreshCw size={14} className={loading ? 'spin' : ''} />
            Refresh
          </button>
        </div>

        {error && <div className="modal-error-banner">{error}</div>}

        <div className="modal-body modal-repo-list">
          {loading ? (
            <div className="modal-loading-state">
              <div className="spinner"></div>
              <p>Fetching your GitHub repositories...</p>
            </div>
          ) : filteredRepos.length > 0 ? (
            filteredRepos.map((repo) => {
              const repoId = repo.id;
              const repoFullName = repo.full_name || repo.fullName;
              const isImported = Boolean(importedMap[repoId] || importedMap[repoFullName] || repo.imported);
              const isImporting = importingId === repoId;
              const ownerLogin = repo.owner?.login || repo.owner || 'Unknown';

              return (
                <div key={repo.id} className={`modal-repo-card ${isImported ? 'is-imported' : ''}`}>
                  <div className="modal-repo-info">
                    <div className="modal-repo-title-row">
                      <h3 className="modal-repo-name">{repo.name}</h3>
                      <span className={`badge ${repo.private ? 'badge-private' : 'badge-public'}`}>
                        {repo.private ? <><FiLock size={10} /> Private</> : <><FiGlobe size={10} /> Public</>}
                      </span>
                    </div>

                    <p className="modal-repo-owner">
                      <span>{ownerLogin}</span> / {repo.name}
                    </p>

                    {repo.description ? (
                      <p className="modal-repo-desc">{repo.description}</p>
                    ) : (
                      <p className="modal-repo-desc empty">No description provided</p>
                    )}

                    <div className="modal-repo-meta">
                      <span>Branch: {repo.default_branch || repo.defaultBranch || 'main'}</span>
                      {repo.language && <span>Language: {repo.language}</span>}
                    </div>
                  </div>

                  <div className="modal-repo-action">
                    {isImported ? (
                      <span className="badge badge-imported-pill">
                        <FiCheck size={14} /> Imported
                      </span>
                    ) : (
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => handleImport(repo)}
                        disabled={isImporting}
                        type="button"
                        id={`btn-import-${repo.name}`}
                      >
                        {isImporting ? (
                          <>
                            <span className="spinner" style={{ width: 14, height: 14, borderWidth: 2 }}></span>
                            Importing...
                          </>
                        ) : (
                          <>
                            <FiPlus size={14} />
                            Import Repo
                          </>
                        )}
                      </button>
                    )}
                  </div>
                </div>
              );
            })
          ) : (
            <div className="modal-empty-state">
              <p>{search.trim() ? 'No repositories match your search.' : 'No GitHub repositories found.'}</p>
            </div>
          )}
        </div>

        <div className="modal-footer">
          <button className="btn btn-secondary" onClick={onClose} type="button">
            Done
          </button>
        </div>
      </div>
    </div>
  );
}
