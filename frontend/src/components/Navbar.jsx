import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { FiLogOut, FiGithub, FiMenu, FiX } from 'react-icons/fi';
import { logout } from '../api/github';
import './Navbar.css';

export default function Navbar({ user }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const navigate = useNavigate();

  const handleLogout = async () => {
    try {
      await logout();
    } catch (e) {
      // Ignore API error on logout
    } finally {
      window.location.href = '/';
    }
  };

  return (
    <nav className="navbar" id="main-navbar">
      <div className="navbar-inner container">
        <Link to="/dashboard" className="navbar-brand" id="navbar-brand">
          <div className="brand-icon">
            <FiGithub size={22} />
          </div>
          <span className="brand-text">DeployHub</span>
        </Link>

        <button
          className="navbar-toggle"
          onClick={() => setMenuOpen(!menuOpen)}
          aria-label="Toggle menu"
        >
          {menuOpen ? <FiX size={20} /> : <FiMenu size={20} />}
        </button>

        <div className={`navbar-content ${menuOpen ? 'open' : ''}`}>
          {user && (
            <>
              <Link to="/dashboard" className="navbar-link" id="nav-dashboard">
                Dashboard
              </Link>

              <div className="navbar-user" id="navbar-user">
                <img
                  src={user.avatarUrl}
                  alt={user.username}
                  className="navbar-avatar"
                />
                <span className="navbar-username">{user.username}</span>
              </div>

              <button
                className="btn btn-ghost btn-sm"
                onClick={handleLogout}
                id="btn-logout"
              >
                <FiLogOut size={16} />
                Logout
              </button>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
