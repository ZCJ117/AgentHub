const TOKEN_KEY = 'ai_agent_token'
const EXPIRY_KEY = 'ai_agent_token_expiry'

export function getToken() {
  const token = localStorage.getItem(TOKEN_KEY)
  const expiry = localStorage.getItem(EXPIRY_KEY)
  if (!token) return null
  if (expiry && Date.now() > Number(expiry)) {
    clearToken()
    return null
  }
  return token
}

export function setToken(token, expiresInMs = 24 * 60 * 60 * 1000) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(EXPIRY_KEY, String(Date.now() + expiresInMs))
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(EXPIRY_KEY)
}

export function isTokenValid() {
  return !!getToken()
}
