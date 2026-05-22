import axios from 'axios'
import { getToken, clearToken, setToken as saveToken } from '@/utils/token'

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || undefined,
  timeout: 60000,
  headers: { 'Content-Type': 'application/json' }
})

apiClient.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  const wsId = localStorage.getItem('ai_agent_workspace_id')
  if (wsId) {
    config.headers['X-Workspace-Id'] = wsId
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => {
    const newToken = response.headers['x-new-token']
    if (newToken) {
      saveToken(newToken)
    }
    const data = response.data
    if (data && (data.code === 200 || data.code === '0000')) {
      return data.data
    }
    const err = new Error(data?.message || 'Request failed')
    err.code = data?.code
    return Promise.reject(err)
  },
  (error) => {
    if (error.response?.status === 401) {
      clearToken()
      localStorage.removeItem('ai_agent_workspace_id')
      window.location.hash = '#/login'
      return Promise.reject(error)
    }
    const msg = error.message || ''
    const isNetworkError =
      msg.includes('Network Error') ||
      msg.includes('Failed to fetch') ||
      !error.response
    if (isNetworkError) {
      error.isNetworkError = true
    }
    return Promise.reject(error)
  }
)

export default apiClient
