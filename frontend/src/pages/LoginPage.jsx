import { FiGithub, FiArrowRight, FiBox, FiGitPullRequest, FiZap } from 'react-icons/fi';
import { getGitHubLoginUrl } from '../api/github';
import './LoginPage.css';

export default function LoginPage() {
  const handleLogin = () => {
    window.location.href = getGitHubLoginUrl();
  };

  return (
    <div className="login-page" id="login-page">
      {/* Animated background */}
      <div className="login-bg">
        <div className="login-bg-orb login-bg-orb-1"></div>
        <div className="login-bg-orb login-bg-orb-2"></div>
        <div className="login-bg-orb login-bg-orb-3"></div>
        <div className="login-bg-grid"></div>
      </div>

      <div className="login-content">
        {/* Hero */}
        <div className="login-hero animate-fade-in-up">
          <div className="login-badge">
            <FiZap size={14} />
            <span>Ship faster than ever</span>
          </div>

          <h1 className="login-title">
            Deploy your code
            <br />
            <span className="login-title-gradient">in seconds.</span>
          </h1>

          <p className="login-subtitle">
            Import your GitHub repositories, manage your projects, and push code
            — all from one beautiful platform.
          </p>

          <button
            className="btn btn-lg login-btn"
            onClick={handleLogin}
            id="btn-github-login"
          >
            <FiGithub size={22} />
            Continue with GitHub
            <FiArrowRight size={18} />
          </button>

          <p className="login-hint">
            We'll request access to your repositories for import & push.
          </p>
        </div>

        {/* Feature cards */}
        <div className="login-features animate-fade-in-up delay-3">
          <div className="feature-card glass-card">
            <div className="feature-icon">
              <FiBox size={24} />
            </div>
            <h3>Import Repos</h3>
            <p>Browse and select any of your GitHub repositories to import to the platform.</p>
          </div>

          <div className="feature-card glass-card">
            <div className="feature-icon">
              <FiGitPullRequest size={24} />
            </div>
            <h3>Push Code</h3>
            <p>Push files directly to your GitHub repositories from within the platform.</p>
          </div>

          <div className="feature-card glass-card">
            <div className="feature-icon">
              <FiZap size={24} />
            </div>
            <h3>One-Click Deploy</h3>
            <p>Select a repo and deploy it instantly with automated CI/CD pipelines.</p>
          </div>
        </div>
      </div>
    </div>
  );
}
