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
 * Sign up with email and password.
 */
export async function signUp(name, email, password) {
  const data = await apiFetch('/auth/signup', {
    method: 'POST',
    body: JSON.stringify({ name, email, password }),
  });
  if (data && data.token) {
    setAuthToken(data.token);
  }
  return data;
}

/**
 * Login with email and password.
 */
export async function loginWithEmail(email, password) {
  const data = await apiFetch('/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
  if (data && data.token) {
    setAuthToken(data.token);
  }
  return data;
}

/**
 * Login/signup with Google OAuth.
 * @param {string} credential - The Google ID token from Google Sign-In.
 */
export async function loginWithGoogle(credential) {
  const data = await apiFetch('/auth/google', {
    method: 'POST',
    body: JSON.stringify({ credential }),
  });
  if (data && data.token) {
    setAuthToken(data.token);
  }
  return data;
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

// ==================== AI Agent APIs ====================

/**
 * Generate a CI/CD pipeline YAML for a repository.
 */
export async function generatePipeline(owner, repo, branch) {
  return apiFetch('/agents/pipeline/generate', {
    method: 'POST',
    body: JSON.stringify({ owner, repo, branch }),
  });
}

/**
 * Run a code review on a repository.
 */
export async function runCodeReview(owner, repo, branch) {
  return apiFetch('/agents/review/analyze', {
    method: 'POST',
    body: JSON.stringify({ owner, repo, branch }),
  });
}

/**
 * Analyze pipeline logs for errors and root causes.
 */
export async function analyzeLogs(logText) {
  return apiFetch('/agents/logs/analyze', {
    method: 'POST',
    body: JSON.stringify({ log_text: logText }),
  });
}

/**
 * Generate a deployment plan for a repository.
 */
export async function generateDeploymentPlan(owner, repo, branch) {
  return apiFetch('/agents/deploy/plan', {
    method: 'POST',
    body: JSON.stringify({ owner, repo, branch }),
  });
}

/**
 * Get pipeline generation history.
 */
export async function getPipelineHistory(owner, repo) {
  const params = owner && repo ? `?owner=${owner}&repo=${repo}` : '';
  return apiFetch(`/agents/pipeline/history${params}`);
}

/**
 * Get code review history.
 */
export async function getReviewHistory() {
  return apiFetch('/agents/review/history');
}


/**
 * Multi-Agent Self-Healing Loop: Remediate failed CI/CD pipeline using logs.
 */
export async function remediatePipeline(owner, repo, branch, failedYaml, errorLogs) {
  return apiFetch('/agents/pipeline/remediate', {
    method: 'POST',
    body: JSON.stringify({
      owner,
      repo,
      branch,
      failed_yaml: failedYaml,
      error_logs: errorLogs,
    }),
  });
}

/**
 * Run code review on a Pull Request diff.
 */
export async function runPullRequestReview(owner, repo, prNumber, diff, title, author) {
  return apiFetch('/agents/review/pull-request', {
    method: 'POST',
    body: JSON.stringify({
      owner,
      repo,
      pr_number: prNumber,
      diff,
      title,
      author,
    }),
  });
}

/**
 * Execute production deployment lifecycle (Docker -> Deploy -> Health Check -> Auto-rollback on fail).
 */
export async function executeDeployment(owner, repo, commitSha, environment = 'production', imageTag = null, simulateFailure = false) {
  return apiFetch('/agents/deploy/execute', {
    method: 'POST',
    body: JSON.stringify({
      owner,
      repo,
      commit_sha: commitSha,
      environment,
      image_tag: imageTag,
      simulate_health_failure: simulateFailure,
    }),
  });
}

/**
 * Rollback deployment to a previous version from Version History.
 */
export async function rollbackDeployment(owner, repo, targetVersion = null) {
  return apiFetch('/agents/deploy/rollback', {
    method: 'POST',
    body: JSON.stringify({
      owner,
      repo,
      target_version: targetVersion,
    }),
  });
}

/**
 * Get deployment version history.
 */
export async function getDeploymentHistory(owner, repo) {
  const params = owner && repo ? `?owner=${owner}&repo=${repo}` : '';
  return apiFetch(`/agents/deploy/history${params}`);
}

/**
 * Execute arbitrary multi-agent workflow on AI Orchestrator.
 */
export async function executeWorkflow(workflow, payload = {}) {
  return apiFetch('/agents/orchestrator/workflow', {
    method: 'POST',
    body: JSON.stringify({ workflow, payload }),
  });
}
