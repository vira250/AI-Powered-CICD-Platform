import Sidebar from './Sidebar';
import './DashboardLayout.css';

export default function DashboardLayout({ user, children }) {
  return (
    <div className="dashboard-layout">
      <Sidebar user={user} />
      <main className="dashboard-main">
        <div className="dashboard-main-content">
          {children}
        </div>
      </main>
    </div>
  );
}
