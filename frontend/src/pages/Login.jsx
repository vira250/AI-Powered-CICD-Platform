import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import api from '../api.js'

export default function Login() {
  const [params] = useSearchParams()
  const navigate = useNavigate()

  const [isRegister, setIsRegister] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [name, setName] = useState('')
  const [loading, setLoading] = useState(false)
  const [errorMsg, setErrorMsg] = useState('')

  // OAuth callback lands here with ?token=
  useEffect(() => {
    const token = params.get('token')
    if (token) {
      localStorage.setItem('session_token', token)
      navigate('/', { replace: true })
    }
    if (params.get('error')) {
      setErrorMsg('GitHub login failed — please try again.')
    }
  }, [params, navigate])

  const handleAuth = async (e) => {
    e.preventDefault()
    setLoading(true)
    setErrorMsg('')

    try {
      const endpoint = isRegister ? '/auth/register' : '/auth/login'
      const payload = isRegister ? { email, password, name } : { email, password }
      const { data } = await api.post(endpoint, payload)

      if (data.token) {
        localStorage.setItem('session_token', data.token)
        navigate('/', { replace: true })
      }
    } catch (err) {
      setErrorMsg(err.response?.data?.error || 'Authentication failed. Please check your credentials.')
    } finally {
      setLoading(false)
    }
  }

  const loginWithGithub = async () => {
    try {
      const { data } = await api.get('/auth/github')
      window.location.href = data.url
    } catch (err) {
      setErrorMsg('Failed to initiate GitHub OAuth. Check backend connection.')
    }
  }

  return (
    <div className="login-container">
      <div className="login-box">
        <div className="login-brand">
          <div className="login-brand-icon">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
            </svg>
          </div>
          <h1 style={{ fontSize: '22px', fontWeight: 700, margin: '0 0 6px 0', letterSpacing: '-0.02em' }}>
            {isRegister ? 'Create NexusPipe Account' : 'Welcome to NexusPipe'}
          </h1>
          <p className="muted" style={{ fontSize: '13px', margin: 0 }}>
            Autonomous Multi-Agent CI/CD Platform for GitHub Actions
          </p>
        </div>

        {errorMsg && (
          <div className="notice-toast" style={{ borderColor: 'var(--signal-danger-border)', backgroundColor: 'var(--signal-danger-bg)', color: 'var(--signal-danger)' }}>
            <span>{errorMsg}</span>
          </div>
        )}

        <form onSubmit={handleAuth} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
          {isRegister && (
            <div>
              <label style={{ display: 'block', fontSize: '12.5px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
                Full Name
              </label>
              <input
                type="text"
                placeholder="Rohan Sharma"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </div>
          )}

          <div>
            <label style={{ display: 'block', fontSize: '12.5px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Email Address
            </label>
            <input
              type="email"
              placeholder="developer@nexuspipe.ai"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>

          <div>
            <label style={{ display: 'block', fontSize: '12.5px', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '6px' }}>
              Password
            </label>
            <input
              type="password"
              placeholder="••••••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={4}
            />
          </div>

          <button type="submit" className="btn primary lg" style={{ width: '100%', marginTop: '6px' }} disabled={loading}>
            {loading ? (isRegister ? 'Creating Account…' : 'Signing In…') : (isRegister ? 'Create Account' : 'Sign In')}
          </button>
        </form>

        <div className="login-divider">
          <span>OR</span>
        </div>

        <button className="btn github lg" style={{ width: '100%' }} onClick={loginWithGithub}>
          <svg height="18" width="18" viewBox="0 0 16 16" fill="currentColor">
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/>
          </svg>
          Continue with GitHub OAuth
        </button>

        <div style={{ marginTop: '24px', textAlign: 'center', fontSize: '13px', color: 'var(--text-tertiary)' }}>
          {isRegister ? (
            <p style={{ margin: 0 }}>
              Already registered?{' '}
              <button
                type="button"
                className="btn ghost sm"
                style={{ color: 'var(--brand-primary)', padding: '2px 6px', display: 'inline' }}
                onClick={() => setIsRegister(false)}
              >
                Sign In
              </button>
            </p>
          ) : (
            <p style={{ margin: 0 }}>
              Need an account?{' '}
              <button
                type="button"
                className="btn ghost sm"
                style={{ color: 'var(--brand-primary)', padding: '2px 6px', display: 'inline' }}
                onClick={() => setIsRegister(true)}
              >
                Create Account
              </button>
            </p>
          )}
        </div>
      </div>
    </div>
  )
}
