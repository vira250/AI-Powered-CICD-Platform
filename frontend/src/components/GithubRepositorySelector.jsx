import { useEffect, useMemo, useState } from 'react';
import { FiCheck, FiChevronRight, FiGlobe, FiLock, FiRefreshCw, FiSearch } from 'react-icons/fi';
import { getGithubRepos, selectGithubRepository } from '../api/github';
import './GithubRepositorySelector.css';

function normalizeRepository(repository) {
  return {
    id: repository.id,
    name: repository.name,
    full_name: repository.full_name || repository.fullName || `${repository.owner?.login || repository.owner || ''}/${repository.name}`,
    private: Boolean(repository.private ?? repository.privateRepository),
    html_url: repository.html_url || repository.htmlUrl || '',
    default_branch: repository.default_branch || repository.defaultBranch || 'main',
    description: repository.description || '',
    owner: {
      login: repository.owner?.login || repository.owner_login || repository.ownerLogin || repository.owner || '',
    },
  };
}

export default function GithubRepositorySelector({ selectedRepositoryId, onRepositorySelected }) {
  const [repositories, setRepositories] = useState([]);
  const [loading, setLoading] = useState(true);
  const [savingId, setSavingId] = useState(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState(null);

  useEffect(() => {
    loadRepositories();
  }, []);

  async function loadRepositories() {
    setLoading(true);
    setError(null);

    try {
      const data = await getGithubRepos();
      setRepositories(Array.isArray(data) ? data.map(normalizeRepository) : []);
    } catch (exception) {
      setError(exception.message || 'Failed to load repositories');
      setRepositories([]);
    } finally {
      setLoading(false);
    }
  }

  async function handleSelect(repository) {
    setSavingId(repository.id);

    try {
      const savedRepository = await selectGithubRepository(repository);
      onRepositorySelected?.(normalizeRepository(savedRepository));
    } catch (exception) {
      setError(exception.message || 'Failed to store repository selection');
    } finally {
      setSavingId(null);
    }
  }

  const filteredRepositories = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) {
      return repositories;
    }

    return repositories.filter((repository) => {
      const ownerLogin = repository.owner?.login || '';
      return (
        repository.name.toLowerCase().includes(query) ||
        repository.full_name.toLowerCase().includes(query) ||
        ownerLogin.toLowerCase().includes(query) ||
        repository.description.toLowerCase().includes(query)
      );
    });
  }, [repositories, search]);

  return (
    <section className="repo-selector glass-card animate-fade-in delay-2">
      <div className="repo-selector-header">
        <div>
          <h2 className="repo-selector-title">GitHub repositories</h2>
          <p className="repo-selector-subtitle">
            Choose the repository your CI/CD workflow should operate on.
          </p>
        </div>

        <button className="btn btn-secondary btn-sm" onClick={loadRepositories} type="button" id="btn-refresh-repos">
          <FiRefreshCw size={14} />
          Refresh
        </button>
      </div>

      <div className="repo-selector-search">
        <FiSearch className="repo-selector-search-icon" size={18} />
        <input
          className="repo-selector-input"
          type="search"
          placeholder="Search by repository name, owner, or description"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          id="repo-selector-search"
        />
      </div>

      {error && (
        <div className="repo-selector-error" role="alert">
          {error}
        </div>
      )}

      {loading ? (
        <div className="repo-selector-loading">
          <div className="spinner"></div>
          <p>Loading repositories...</p>
        </div>
      ) : filteredRepositories.length > 0 ? (
        <div className="repo-selector-list">
          {filteredRepositories.map((repository) => {
            const isSelected = selectedRepositoryId === repository.id;
            const isSaving = savingId === repository.id;

            return (
              <article
                key={repository.id}
                className={`repo-selector-item ${isSelected ? 'is-selected' : ''}`}
                id={`repo-selector-item-${repository.id}`}
                onClick={() => handleSelect(repository)}
                role="button"
                tabIndex={0}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    handleSelect(repository);
                  }
                }}
              >
                <div className="repo-selector-item-main">
                  <div className="repo-selector-item-title-row">
                    <h3 className="repo-selector-item-title">{repository.name}</h3>
                    <span className={`badge ${repository.private ? 'badge-private' : 'badge-public'}`}>
                      {repository.private ? <><FiLock size={10} /> Private</> : <><FiGlobe size={10} /> Public</>}
                    </span>
                  </div>

                  <p className="repo-selector-item-owner">{repository.owner?.login || 'Unknown owner'}</p>

                  {repository.description ? (
                    <p className="repo-selector-item-description">{repository.description}</p>
                  ) : (
                    <p className="repo-selector-item-description repo-selector-item-description-empty">
                      No description provided.
                    </p>
                  )}

                  <div className="repo-selector-item-meta">
                    <span>Default branch: {repository.default_branch}</span>
                    <span>ID: {repository.id}</span>
                  </div>
                </div>

                <div className="repo-selector-item-action">
                  {isSelected ? (
                    <span className="repo-selector-selected-pill">
                      <FiCheck size={14} /> Selected
                    </span>
                  ) : (
                    <button className="btn btn-primary btn-sm" type="button" disabled={isSaving}>
                      {isSaving ? 'Saving...' : 'Select'}
                      {!isSaving && <FiChevronRight size={14} />}
                    </button>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      ) : (
        <div className="repo-selector-empty">
          <h3>No repositories found</h3>
          <p>
            {search.trim()
              ? 'Try a different search term.'
              : 'No repositories are available for the authenticated GitHub user.'}
          </p>
        </div>
      )}
    </section>
  );
}