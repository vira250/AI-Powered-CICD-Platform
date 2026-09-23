import { useState, useCallback } from 'react';
import {
  FiGithub, FiMail, FiLock, FiUser, FiEye, FiEyeOff,
  FiAlertCircle, FiCheckCircle, FiZap
} from 'react-icons/fi';
import { getGitHubLoginUrl, signUp, loginWithEmail, loginWithGoogle } from '../api/github';
import './LoginPage.css';

/* Google "G" logo as inline SVG */
function GoogleIcon({ size = 18 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48">
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
      <path fill="#FBBC05" d="M10.53 28.59a14.5 14.5 0 0 1 0-9.18l-7.98-6.19a24.0 24.0 0 0 0 0 21.56l7.98-6.19z"/>
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
    </svg>
  );
}

function getPasswordStrength(password) {
  if (!password) return { level: '', label: '' };
  let score = 0;
  if (password.length >= 8) score++;
  if (password.length >= 12) score++;
  if (/[A-Z]/.test(password) && /[a-z]/.test(password)) score++;
  if (/\d/.test(password)) score++;
  if (/[^A-Za-z0-9]/.test(password)) score++;

  if (score <= 1) return { level: 'weak', label: 'Weak' };
  if (score === 2) return { level: 'fair', label: 'Fair' };
  if (score === 3) return { level: 'good', label: 'Good' };
  return { level: 'strong', label: 'Strong' };
}

export default function LoginPage({ defaultTab = 'login', onAuthSuccess }) {
  const [activeTab, setActiveTab] = useState(defaultTab);
  const [loading, setLoading] = useState(false);
  const [toast, setToast] = useState(null);

  // Login form state
  const [loginEmail, setLoginEmail] = useState('');
  const [loginPassword, setLoginPassword] = useState('');
  const [showLoginPassword, setShowLoginPassword] = useState(false);

  // Sign up form state
  const [signupName, setSignupName] = useState('');
  const [signupEmail, setSignupEmail] = useState('');
  const [signupPassword, setSignupPassword] = useState('');
  const [signupConfirm, setSignupConfirm] = useState('');
  const [showSignupPassword, setShowSignupPassword] = useState(false);

  // Field errors
  const [errors, setErrors] = useState({});

  const showToast = useCallback((message, type = 'error') => {
    setToast({ message, type });
    setTimeout(() => setToast(null), 4000);
  }, []);

  const clearErrors = () => setErrors({});

  // ---- Login Handler ----
  async function handleLogin(e) {
    e.preventDefault();
    clearErrors();

    const newErrors = {};
    if (!loginEmail.trim()) newErrors.loginEmail = 'Email is required';
    if (!loginPassword) newErrors.loginPassword = 'Password is required';

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    setLoading(true);
    try {
      const data = await loginWithEmail(loginEmail.trim(), loginPassword);
      showToast('Welcome back!', 'success');
      if (onAuthSuccess) onAuthSuccess(data);
    } catch (err) {
      showToast(err.message || 'Login failed. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  // ---- Sign Up Handler ----
  async function handleSignUp(e) {
    e.preventDefault();
    clearErrors();

    const newErrors = {};
    if (!signupName.trim()) newErrors.signupName = 'Name is required';
    if (!signupEmail.trim()) newErrors.signupEmail = 'Email is required';
    else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(signupEmail))
      newErrors.signupEmail = 'Enter a valid email address';
    if (!signupPassword) newErrors.signupPassword = 'Password is required';
    else if (signupPassword.length < 8) newErrors.signupPassword = 'Must be at least 8 characters';
    if (signupPassword !== signupConfirm) newErrors.signupConfirm = 'Passwords do not match';

    if (Object.keys(newErrors).length > 0) {
      setErrors(newErrors);
      return;
    }

    setLoading(true);
    try {
      const data = await signUp(signupName.trim(), signupEmail.trim(), signupPassword);
      showToast('Account created successfully!', 'success');
      if (onAuthSuccess) onAuthSuccess(data);
    } catch (err) {
      showToast(err.message || 'Sign up failed. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  // ---- GitHub OAuth ----
  function handleGitHub() {
    window.location.href = getGitHubLoginUrl();
  }

  // ---- Google OAuth ----
  async function handleGoogleLogin() {
    try {
      // Use Google Identity Services (GSI) popup
      if (!window.google?.accounts?.id) {
        showToast('Google Sign-In is not configured. Please set up your Google Client ID.');
        return;
      }
      // The callback is set up in the script load; trigger prompt
      window.google.accounts.id.prompt();
    } catch (err) {
      showToast('Google Sign-In failed. Please try again.');
    }
  }

  // Set up Google callback if GSI script is loaded
  if (typeof window !== 'undefined' && !window.__gsiCallbackSet) {
    window.__gsiCallbackSet = true;
    const checkGsi = setInterval(() => {
      if (window.google?.accounts?.id) {
        clearInterval(checkGsi);
        const metaTag = document.querySelector('meta[name="google-client-id"]');
        const gClientId = metaTag?.content;
        if (gClientId) {
          window.google.accounts.id.initialize({
            client_id: gClientId,
            callback: async (response) => {
              try {
                setLoading(true);
                const data = await loginWithGoogle(response.credential);
                showToast('Welcome!', 'success');
                if (onAuthSuccess) onAuthSuccess(data);
              } catch (err) {
                showToast(err.message || 'Google sign-in failed.');
              } finally {
                setLoading(false);
              }
            },
          });
        }
      }
    }, 500);
    // Stop checking after 10s
    setTimeout(() => clearInterval(checkGsi), 10000);
  }

  const passwordStrength = getPasswordStrength(signupPassword);

  return (
    <div className="auth-page" id="auth-page">
      {/* Animated background */}
      <div className="auth-bg">
        <div className="auth-bg-orb auth-bg-orb-1"></div>
        <div className="auth-bg-orb auth-bg-orb-2"></div>
        <div className="auth-bg-orb auth-bg-orb-3"></div>
        <div className="auth-bg-orb auth-bg-orb-4"></div>
        <div className="auth-bg-grid"></div>
      </div>

      {/* Toast */}
      {toast && (
        <div className={`auth-toast auth-toast--${toast.type}`}>
          {toast.type === 'error' ? <FiAlertCircle size={16} /> : <FiCheckCircle size={16} />}
          {toast.message}
        </div>
      )}

      {/* Auth Card */}
      <div className="auth-card">
        {/* Branding */}
        <div className="auth-brand">
          <div className="auth-logo">
            <FiZap size={26} />
          </div>
          <h1>DeployPilot</h1>
          <p>{activeTab === 'login' ? 'Welcome back' : 'Create your account'}</p>
        </div>

        {/* Tab Toggle */}
        <div className="auth-tabs">
          <div className={`auth-tab-indicator ${activeTab === 'signup' ? 'auth-tab-indicator--signup' : ''}`}></div>
          <button
            className={`auth-tab ${activeTab === 'login' ? 'auth-tab--active' : ''}`}
            onClick={() => { setActiveTab('login'); clearErrors(); }}
            type="button"
            id="tab-login"
          >
            Log In
          </button>
          <button
            className={`auth-tab ${activeTab === 'signup' ? 'auth-tab--active' : ''}`}
            onClick={() => { setActiveTab('signup'); clearErrors(); }}
            type="button"
            id="tab-signup"
          >
            Sign Up
          </button>
        </div>

        {/* Login Form */}
        {activeTab === 'login' && (
          <form className="auth-form" onSubmit={handleLogin} key="login-form">
            <div className="auth-input-group">
              <label className="auth-input-label" htmlFor="login-email">Email</label>
              <div className="auth-input-wrapper">
                <input
                  id="login-email"
                  type="email"
                  className={`auth-input ${errors.loginEmail ? 'auth-input--error' : ''}`}
                  placeholder="you@example.com"
                  value={loginEmail}
                  onChange={e => setLoginEmail(e.target.value)}
                  autoComplete="email"
                  autoFocus
                />
                <span className="auth-input-icon"><FiMail size={16} /></span>
              </div>
              {errors.loginEmail && <span className="auth-error-text"><FiAlertCircle size={12} /> {errors.loginEmail}</span>}
            </div>

            <div className="auth-input-group">
              <label className="auth-input-label" htmlFor="login-password">Password</label>
              <div className="auth-input-wrapper">
                <input
                  id="login-password"
                  type={showLoginPassword ? 'text' : 'password'}
                  className={`auth-input ${errors.loginPassword ? 'auth-input--error' : ''}`}
                  placeholder="Enter your password"
                  value={loginPassword}
                  onChange={e => setLoginPassword(e.target.value)}
                  autoComplete="current-password"
                />
                <span className="auth-input-icon"><FiLock size={16} /></span>
                <button
                  type="button"
                  className="auth-password-toggle"
                  onClick={() => setShowLoginPassword(!showLoginPassword)}
                  tabIndex={-1}
                  aria-label="Toggle password visibility"
                >
                  {showLoginPassword ? <FiEyeOff size={16} /> : <FiEye size={16} />}
                </button>
              </div>
              {errors.loginPassword && <span className="auth-error-text"><FiAlertCircle size={12} /> {errors.loginPassword}</span>}
            </div>

            <button
              type="submit"
              className="auth-submit-btn"
              disabled={loading}
              id="btn-login"
            >
              {loading ? <div className="spinner"></div> : <>Log In</>}
            </button>
          </form>
        )}

        {/* Sign Up Form */}
        {activeTab === 'signup' && (
          <form className="auth-form" onSubmit={handleSignUp} key="signup-form">
            <div className="auth-input-group">
              <label className="auth-input-label" htmlFor="signup-name">Full Name</label>
              <div className="auth-input-wrapper">
                <input
                  id="signup-name"
                  type="text"
                  className={`auth-input ${errors.signupName ? 'auth-input--error' : ''}`}
                  placeholder="John Doe"
                  value={signupName}
                  onChange={e => setSignupName(e.target.value)}
                  autoComplete="name"
                  autoFocus
                />
                <span className="auth-input-icon"><FiUser size={16} /></span>
              </div>
              {errors.signupName && <span className="auth-error-text"><FiAlertCircle size={12} /> {errors.signupName}</span>}
            </div>

            <div className="auth-input-group">
              <label className="auth-input-label" htmlFor="signup-email">Email</label>
              <div className="auth-input-wrapper">
                <input
                  id="signup-email"
                  type="email"
                  className={`auth-input ${errors.signupEmail ? 'auth-input--error' : ''}`}
                  placeholder="you@example.com"
                  value={signupEmail}
                  onChange={e => setSignupEmail(e.target.value)}
                  autoComplete="email"
                />
                <span className="auth-input-icon"><FiMail size={16} /></span>
              </div>
              {errors.signupEmail && <span className="auth-error-text"><FiAlertCircle size={12} /> {errors.signupEmail}</span>}
            </div>

            <div className="auth-input-group">
              <label className="auth-input-label" htmlFor="signup-password">Password</label>
              <div className="auth-input-wrapper">
                <input
                  id="signup-password"
                  type={showSignupPassword ? 'text' : 'password'}
                  className={`auth-input ${errors.signupPassword ? 'auth-input--error' : ''}`}
                  placeholder="Min. 8 characters"
                  value={signupPassword}
                  onChange={e => setSignupPassword(e.target.value)}
                  autoComplete="new-password"
                />
                <span className="auth-input-icon"><FiLock size={16} /></span>
                <button
                  type="button"
                  className="auth-password-toggle"
                  onClick={() => setShowSignupPassword(!showSignupPassword)}
                  tabIndex={-1}
                  aria-label="Toggle password visibility"
                >
                  {showSignupPassword ? <FiEyeOff size={16} /> : <FiEye size={16} />}
                </button>
              </div>
              {errors.signupPassword && <span className="auth-error-text"><FiAlertCircle size={12} /> {errors.signupPassword}</span>}
              {signupPassword && (
                <div className="auth-password-strength">
                  <div className="auth-strength-bar">
                    <div className={`auth-strength-fill auth-strength-fill--${passwordStrength.level}`}></div>
                  </div>
                  <span className="auth-strength-text">{passwordStrength.label}</span>
                </div>
              )}
            </div>

            <div className="auth-input-group">
              <label className="auth-input-label" htmlFor="signup-confirm">Confirm Password</label>
              <div className="auth-input-wrapper">
                <input
                  id="signup-confirm"
                  type="password"
                  className={`auth-input ${errors.signupConfirm ? 'auth-input--error' : ''}`}
                  placeholder="Re-enter password"
                  value={signupConfirm}
                  onChange={e => setSignupConfirm(e.target.value)}
                  autoComplete="new-password"
                />
                <span className="auth-input-icon"><FiLock size={16} /></span>
              </div>
              {errors.signupConfirm && <span className="auth-error-text"><FiAlertCircle size={12} /> {errors.signupConfirm}</span>}
            </div>

            <button
              type="submit"
              className="auth-submit-btn"
              disabled={loading}
              id="btn-signup"
            >
              {loading ? <div className="spinner"></div> : <>Create Account</>}
            </button>
          </form>
        )}

        {/* Divider */}
        <div className="auth-divider">
          <div className="auth-divider-line"></div>
          <span className="auth-divider-text">or continue with</span>
          <div className="auth-divider-line"></div>
        </div>

        {/* OAuth Buttons */}
        <div className="auth-oauth-row">
          <button
            className="auth-oauth-btn auth-oauth-btn--github"
            onClick={handleGitHub}
            type="button"
            id="btn-github-login"
          >
            <FiGithub size={18} />
            GitHub
          </button>
          <button
            className="auth-oauth-btn auth-oauth-btn--google"
            onClick={handleGoogleLogin}
            type="button"
            id="btn-google-login"
          >
            <GoogleIcon size={18} />
            Google
          </button>
        </div>

        {/* Footer */}
        <div className="auth-footer">
          <p>
            By continuing, you agree to our{' '}
            <a href="#terms">Terms of Service</a> and{' '}
            <a href="#privacy">Privacy Policy</a>
          </p>
        </div>
      </div>
    </div>
  );
}
