import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { FiArrowRight, FiBox, FiExternalLink, FiPackage, FiPlus, FiFolder, FiTrash2, FiZap } from 'react-icons/fi';
import { getImportedRepos, getSelectedGithubRepository, removeImportedRepo } from '../api/github';
import Navbar from '../components/Navbar';
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
    owner: {
      login: repo.owner?.login || repo.owner_login || repo.ownerLogin || repo.owner || '',
    },
  };
}

export default function DashboardPage({ user }) {
  const [importedRepos, setImportedRepos] = useState([]);
  const [selectedRepo, setSelectedRepo] = useState(null);
  const [isImportModalOpen, setIsImportModalOpen] = useState(false);
  const navigate = useNavigate();

  useEffect(() => {
    fetchImportedRepos();
    fetchSelectedRepo();
  }, []);

  async function fetchImportedRepos() {
    try {
      const data = await getImportedRepos();
      setImportedRepos(Array.isArray(data) ? data : []);
    } catch (error) {
      setImportedRepos([]);
    }
  }

  async function fetchSelectedRepo() {
    try {
      const data = await getSelectedGithubRepository();
      if (data && data.id) {
        setSelectedRepo(normalizeRepository(data));
      }
    } catch (error) {
      // No selected repository stored yet.
    }
  }

  function handleRepositorySelected(repo) {
    setSelectedRepo(normalizeRepository(repo));
  }

  function handleRepoImported() {
    fetchImportedRepos();
  }

  async function handleRemoveImport(e, id) {
    e.stopPropagation();
    if (confirm('Are you sure you want to remove this repository from your imported projects?')) {
      try {
        await removeImportedRepo(id);
        setImportedRepos((prev) => prev.filter((r) => r.id !== id));
      } catch (err) {
        alert('Failed to remove repository');
      }
    }
  }

  return (
    <div className="dashboard-page" id="dashboard-page">
      <Navbar user={user} />

      <main className="page">
        <div className="container">
          <div className="dashboard-header animate-fade-in">
            <div>
              <h1 className="dashboard-title">
                Welcome back, <span className="text-gradient">{user?.name || user?.username}</span>
              </h1>
              <p className="dashboard-subtitle">
                Import and manage repositories to configure CI/CD pipelines and inspect folder structures.
              </p>
            </div>

            <button
              className="btn btn-primary btn-lg btn-import-floating"
              onClick={() => setIsImportModalOpen(true)}
              type="button"
              id="btn-open-import-modal"
            >
              <FiPlus size={18} />
              Import Repository
            </button>
          </div>

          {selectedRepo && (
            <section className="selected-repo-panel glass-card animate-fade-in delay-1">
              <div className="selected-repo-copy">
                <span className="selected-repo-label">Selected repository</span>
                <h2 className="selected-repo-title">{selectedRepo.name}</h2>
                <p className="selected-repo-meta">
                  {selectedRepo.owner?.login} · {selectedRepo.default_branch} · {selectedRepo.private ? 'Private' : 'Public'}
                </p>
              </div>

              <div className="selected-repo-actions">
                <button
                  className="btn btn-secondary"
                  onClick={() => setSelectedRepo(null)}
                  id="btn-clear-selection"
                  type="button"
                >
                  Clear
                </button>
                <button
                  className="btn btn-primary"
                  onClick={() => navigate(`/repo/${selectedRepo.owner?.login}/${selectedRepo.name}`)}
                  id="btn-continue-selection"
                  type="button"
                >
                  Inspect Structure
                  <FiArrowRight size={16} />
                </button>
              </div>
            </section>
          )}

          <section className="repos-section animate-fade-in delay-2">
            <div className="section-header">
              <h2 className="section-title">
                <FiBox size={20} />
                Imported Repositories
              </h2>
              <span className="section-count">{importedRepos.length}</span>
            </div>

            {importedRepos.length > 0 ? (
              <div className="imported-grid">
                {importedRepos.map((repo) => (
                  <div
                    key={repo.id}
                    className="imported-card glass-card"
                    onClick={() => navigate(`/repo/${repo.owner}/${repo.name}`)}
                    id={`imported-${repo.name}`}
                    role="button"
                    tabIndex={0}
                  >
                    <div className="imported-card-top">
                      <div className="imported-card-icon">
                        <FiBox size={18} />
                      </div>
                      <div className="imported-card-top-actions">
                        <button
                          className="icon-btn danger"
                          onClick={(e) => handleRemoveImport(e, repo.id)}
                          title="Remove from imported repos"
                          type="button"
                        >
                          <FiTrash2 size={14} />
                        </button>
                        <FiExternalLink size={14} className="imported-card-link" />
                      </div>
                    </div>

                    <h4 className="imported-card-name">{repo.name}</h4>
                    <p className="imported-card-owner">{repo.owner}</p>

                    {repo.description && (
                      <p className="imported-card-desc">{repo.description}</p>
                    )}

                    <div className="imported-card-footer">
                      {repo.language ? (
                        <span className="badge badge-language">
                          {repo.language}
                        </span>
                      ) : (
                        <span></span>
                      )}
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <button
                          className="btn-card-generate"
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(`/repo/${repo.owner}/${repo.name}?action=generate`);
                          }}
                          title="Generate AI Pipeline"
                          type="button"
                        >
                          <FiZap size={13} />
                          Pipeline
                        </button>
                        <span className="btn-inspect-link">
                          Files <FiFolder size={14} />
                        </span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="empty-state glass-card">
                <FiPackage size={48} />
                <h3>No imported repositories yet</h3>
                <p>Click below to open the repository selector and import your first GitHub repository.</p>
                <button
                  className="btn btn-primary"
                  onClick={() => setIsImportModalOpen(true)}
                  style={{ marginTop: '12px' }}
                >
                  <FiPlus size={16} />
                  Import Repository
                </button>
              </div>
            )}
          </section>

          <GithubRepositorySelector
            selectedRepositoryId={selectedRepo?.id}
            onRepositorySelected={handleRepositorySelected}
          />
        </div>
      </main>

      <RepoImportModal
        isOpen={isImportModalOpen}
        onClose={() => setIsImportModalOpen(false)}
        importedRepos={importedRepos}
        onRepoImported={handleRepoImported}
      />
    </div>
  );
}
