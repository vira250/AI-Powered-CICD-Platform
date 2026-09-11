import { useState, useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { getUser, setAuthToken } from './api/github';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';
import RepoDetailPage from './pages/RepoDetailPage';
import PipelinesPage from './pages/PipelinesPage';
import CodeReviewPage from './pages/CodeReviewPage';
import LogAnalysisPage from './pages/LogAnalysisPage';
import DeploymentsPage from './pages/DeploymentsPage';
import SettingsPage from './pages/SettingsPage';
import ProtectedRoute from './components/ProtectedRoute';
import './index.css';

function AppShell() {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);
  const [authPending, setAuthPending] = useState(false);
  const [redirectTo, setRedirectTo] = useState(null);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    if (token) {
      setAuthToken(token);
      // Clean query param from URL without page reload
      window.history.replaceState({}, document.title, window.location.pathname);
    }
    const authSuccess = params.get('auth') === 'success' || Boolean(token);
    setAuthPending(authSuccess);
    checkAuth(authSuccess);
  }, []);

  async function checkAuth(fromLogin = false) {
    const maxAttempts = fromLogin ? 5 : 1;

    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      try {
        const userData = await getUser();
        setUser(userData);
        setAuthPending(false);

        if (fromLogin && window.location.pathname !== '/dashboard') {
          setRedirectTo('/dashboard');
        }

        setLoading(false);
        return;
      } catch (error) {
        if (fromLogin && attempt < maxAttempts - 1) {
          await new Promise(resolve => setTimeout(resolve, 250));
          continue;
        }

        setUser(null);
        setAuthPending(false);
        setLoading(false);
        return error;
      }
    }
  }

  return (
    <>
      {redirectTo ? <Navigate to={redirectTo} replace /> : null}
    <Routes>
      <Route
        path="/"
        element={
          loading ? (
            <div className="loading-container" style={{ minHeight: '100vh' }}>
              <div className="spinner spinner-lg"></div>
            </div>
          ) : user ? (
            <Navigate to="/dashboard" replace />
          ) : (
            <LoginPage />
          )
        }
      />

      <Route
        path="/dashboard"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <DashboardPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route
        path="/repo/:owner/:name"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <RepoDetailPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route
        path="/pipelines"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <PipelinesPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route
        path="/reviews"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <CodeReviewPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route
        path="/logs"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <LogAnalysisPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route
        path="/deployments"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <DeploymentsPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route
        path="/settings"
        element={
          <ProtectedRoute user={user} loading={loading || authPending}>
            <SettingsPage user={user} />
          </ProtectedRoute>
        }
      />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AppShell />
    </BrowserRouter>
  );
}

export default App;