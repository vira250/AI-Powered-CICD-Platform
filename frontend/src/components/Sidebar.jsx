import { NavLink, useNavigate } from 'react-router-dom';
import { FiHome, FiGitBranch, FiSearch, FiFileText, FiServer, FiSettings, FiLogOut, FiZap, FiMenu, FiX } from 'react-icons/fi';
import { useState } from 'react';
import { logout } from '../api/github';
import './Sidebar.css';

export default function Sidebar({ user }) {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();

  async function handleLogout() {
    await logout();
    navigate('/');
    window.location.reload();
  }

  const navItems = [
    { path: '/dashboard', icon: FiHome, label: 'Dashboard' },
    { path: '/pipelines', icon: FiGitBranch, label: 'Pipelines' },
    { path: '/reviews', icon: FiSearch, label: 'Code Reviews' },
    { path: '/logs', icon: FiFileText, label: 'Log Analysis' },
    { path: '/deployments', icon: FiServer, label: 'Deployments' },
    { path: '/settings', icon: FiSettings, label: 'Settings' },
  ];

  return (
    <aside className={`sidebar ${collapsed ? 'sidebar-collapsed' : ''}`} id="sidebar">
      <div className="sidebar-header">
        {!collapsed && (
          <div className="sidebar-brand">
            <div className="sidebar-logo">
              <FiZap size={20} />
            </div>
            <span className="sidebar-brand-text">DeployHub</span>
          </div>
        )}
        <button
          className="sidebar-toggle"
          onClick={() => setCollapsed(!collapsed)}
          type="button"
          id="btn-toggle-sidebar"
        >
          {collapsed ? <FiMenu size={18} /> : <FiX size={18} />}
        </button>
      </div>

      <nav className="sidebar-nav">
        {navItems.map((item) => (
          <NavLink
            key={item.path}
            to={item.path}
            className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}
            title={collapsed ? item.label : undefined}
          >
            <item.icon size={18} className="sidebar-link-icon" />
            {!collapsed && <span className="sidebar-link-label">{item.label}</span>}
          </NavLink>
        ))}
      </nav>

      <div className="sidebar-footer">
        {user && (
          <div className="sidebar-user">
            <img src={user.avatarUrl} alt={user.username} className="sidebar-avatar" />
            {!collapsed && (
              <div className="sidebar-user-info">
                <span className="sidebar-user-name">{user.name || user.username}</span>
                <span className="sidebar-user-handle">@{user.username}</span>
              </div>
            )}
          </div>
        )}
        <button
          className="sidebar-link sidebar-logout"
          onClick={handleLogout}
          title={collapsed ? 'Logout' : undefined}
          type="button"
          id="btn-sidebar-logout"
        >
          <FiLogOut size={18} className="sidebar-link-icon" />
          {!collapsed && <span className="sidebar-link-label">Logout</span>}
        </button>
      </div>
    </aside>
  );
}
