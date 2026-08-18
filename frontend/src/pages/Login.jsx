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
      setErrorMsg(err.response?.data?.error || 'Authentication failed. Check your details and try again.')
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
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <div className="logo-badge">⚡ AI CI/CD</div>
          <h1>{isRegister ? 'Create an Account' : 'Welcome Back'}</h1>
          <p>Autonomous AI agents orchestrating your multi-cloud & GitHub Action workflows.</p>
        </div>

        {errorMsg && <div className="error-alert">{errorMsg}</div>}

        <form onSubmit={handleAuth} className="auth-form">
          {isRegister && (
            <div className="form-group">
              <label>Full Name</label>
              <input
                type="text"
                placeholder="John Doe"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
              />
            </div>
          )}

          <div className="form-group">
            <label>Email Address</label>
            <input
              type="email"
              placeholder="developer@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>

          <div className="form-group">
            <label>Password</label>
            <input
              type="password"
              placeholder="••••••••"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={4}
            />
          </div>

          <button type="submit" className="btn primary large full-width" disabled={loading}>
            {loading ? (isRegister ? 'Creating Account…' : 'Signing In…') : (isRegister ? 'Create Account' : 'Sign In')}
          </button>
        </form>

        <div className="divider">
          <span>OR</span>
        </div>

        <button className="btn github-btn full-width" onClick={loginWithGithub}>
          <svg height="20" width="20" viewBox="0 0 16 16" fill="currentColor">
            <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.28.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/>
          </svg>
          Continue with GitHub OAuth
        </button>

        <div className="toggle-auth">
          {isRegister ? (
            <p>Already have an account? <button type="button" className="link-btn" onClick={() => setIsRegister(false)}>Sign In</button></p>
          ) : (
            <p>Need an account? <button type="button" className="link-btn" onClick={() => setIsRegister(true)}>Create New Account</button></p>
          )}
        </div>
      </div>
    </div>
  )
}
