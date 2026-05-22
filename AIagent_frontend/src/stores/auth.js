import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { getToken, setToken, clearToken, isTokenValid } from '@/utils/token'
import { login as loginApi, getProfile } from '@/api/auth'

export const useAuthStore = defineStore('auth', () => {
  const userId = ref(null)
  const username = ref('')
  const nickname = ref('')
  const role = ref('')
  const isChecking = ref(true)
  const loginError = ref('')

  const isLoggedIn = computed(() => !!userId.value && isTokenValid())

  function checkLogin() {
    const valid = isTokenValid()
    if (!valid) {
      logout()
      isChecking.value = false
      return false
    }
    const stored = localStorage.getItem('ai_agent_user')
    if (stored) {
      try {
        const u = JSON.parse(stored)
        userId.value = u.userId
        username.value = u.username
        nickname.value = u.nickname
        role.value = u.role
      } catch { /* ignore corrupt data */ }
    }
    isChecking.value = false
    return true
  }

  async function login(usernameInput, password) {
    loginError.value = ''
    try {
      const data = await loginApi(usernameInput.trim(), password)
      setToken(data.token)
      localStorage.setItem('ai_agent_user', JSON.stringify({
        userId: data.userId,
        username: data.username,
        nickname: data.nickname,
        role: data.role
      }))
      userId.value = data.userId
      username.value = data.username
      nickname.value = data.nickname
      role.value = data.role
      return { ok: true }
    } catch (err) {
      loginError.value = err.message || 'Login failed'
      return { ok: false, error: err.message || 'Login failed' }
    }
  }

  async function refreshProfile() {
    try {
      const data = await getProfile()
      nickname.value = data.nickname
      role.value = data.role
      localStorage.setItem('ai_agent_user', JSON.stringify({
        userId: userId.value,
        username: username.value,
        nickname: data.nickname,
        role: data.role
      }))
    } catch { /* silent fail */ }
  }

  function logout() {
    clearToken()
    localStorage.removeItem('ai_agent_user')
    localStorage.removeItem('ai_agent_workspace_id')
    userId.value = null
    username.value = ''
    nickname.value = ''
    role.value = ''
  }

  return {
    userId, username, nickname, role,
    isChecking, loginError, isLoggedIn,
    checkLogin, login, logout, refreshProfile
  }
})
