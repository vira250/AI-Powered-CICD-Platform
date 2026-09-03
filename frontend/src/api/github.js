const API_BASE = '/api';

export function getAuthToken() {
  return localStorage.getItem('auth_token');
}

export function setAuthToken(token) {
  if (token) {
    localStorage.setItem('auth_token', token);
  } else {
    localStorage.removeItem('auth_token');
  }
}

/**
 * Shared fetch wrapper with credentials & Authorization Bearer token.
 */
async function apiFetch(endpoint, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers,
  };

  const token = getAuthToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE}${endpoint}`, {
    ...options,
    credentials: 'include', // Send session cookies as fallback
    headers,
  });

  if (response.status === 401) {
    setAuthToken(null);
    throw new Error('Unauthorized');
  }

  if (!response.ok) {
    const error = await response.json().catch(() => ({ error: 'Request failed' }));
    throw new Error(error.error || 'Request failed');
  }

  return response.json();
}

/**
 * Get the currently authenticated user.
 */
export async function getUser() {
  const user = await apiFetch('/auth/me');
  if (user && user.token) {
    setAuthToken(user.token);
  }
  return user;
}

/**
 * Logout the current user.
 */
export async function logout() {
  try {
    await apiFetch('/auth/logout', { method: 'POST' });
  } finally {
    setAuthToken(null);
  }
}

/**
 * Get the GitHub login URL (backend will redirect to GitHub).
 */
export function getGitHubLoginUrl() {
  return `${API_BASE}/auth/github`;
}

function buildRepoQuery({ page, perPage } = {}) {
  const params = new URLSearchParams();
  if (page != null) {
    params.set('page', String(page));
  }
  if (perPage != null) {
    params.set('per_page', String(perPage));
  }
  return params.toString() ? `?${params.toString()}` : '';
}

/**
 * List GitHub repositories for the authenticated user.
 */
export async function getGithubRepos(options = {}) {
  return apiFetch(`/github/repos${buildRepoQuery(options)}`);
}

/**
 * Backward-compatible alias for older callers.
 */
export async function getRepos(page = undefined, perPage = undefined) {
  return getGithubRepos({ page, perPage });
}

/**
 * Read the repository currently selected in the backend session.
 */
export async function getSelectedGithubRepository() {
  return apiFetch('/github/selected-repository');
}

/**
 * Store the selected repository in the backend session.
 */
export async function selectGithubRepository(repository) {
  return apiFetch('/github/selected-repository', {
    method: 'POST',
    body: JSON.stringify(repository),
  });
}

/**
 * Get details of a specific repository.
 */
export async function getRepoDetails(owner, repo) {
  return apiFetch(`/repos/${owner}/${repo}`);
}

/**
 * Get the folder structure for a repository.
 */
export async function getRepoStructure(owner, repo, branch) {
  const branchQuery = branch ? `?branch=${encodeURIComponent(branch)}` : '';
  return apiFetch(`/repos/${owner}/${repo}/structure${branchQuery}`);
}

/**
 * Get file content for a single file in a repository.
 */
export async function getFileContent(owner, repo, path, branch) {
  const params = new URLSearchParams({ path });
  if (branch) {
    params.set('branch', branch);
  }
  return apiFetch(`/repos/${owner}/${repo}/file?${params.toString()}`);
}

/**
 * Import a repository to our platform.
 */
export async function importRepo(repoData) {
  return apiFetch('/repos/import', {
    method: 'POST',
    body: JSON.stringify(repoData),
  });
}

/**
 * List all imported repositories.
 */
export async function getImportedRepos() {
  return apiFetch('/repos/imported');
}

/**
 * Remove an imported repository.
 */
export async function removeImportedRepo(id) {
  return apiFetch(`/repos/imported/${id}`, { method: 'DELETE' });
}

/**
 * Push a file to a GitHub repository.
 */
export async function pushFile({ owner, repo, path, content, message, branch }) {
  return apiFetch('/git/push', {
    method: 'POST',
    body: JSON.stringify({ owner, repo, path, content, message, branch }),
  });
}

/**
 * Get the complete RepositoryContext for the Pipeline Generation Agent.
 */
export async function getRepoContext(owner, repo, branch) {
  const branchQuery = branch ? `?branch=${encodeURIComponent(branch)}` : '';
  return apiFetch(`/repos/${owner}/${repo}/context${branchQuery}`);
}
