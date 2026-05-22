<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import HeroSection from '@/components/login/HeroSection.vue'
import LoginForm from '@/components/login/LoginForm.vue'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

const error = ref('')
const loading = ref(false)

async function handleLogin({ username, password }) {
  error.value = ''
  loading.value = true
  try {
    const result = await authStore.login(username, password)
    if (result.ok) {
      await workspaceStore.loadAndSelect()
      router.replace({ name: 'Chat' })
    } else {
      error.value = result.error || 'Login failed'
    }
  } catch (err) {
    error.value = err.message || 'Network error'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-view">
    <HeroSection />
    <LoginForm
      :error="error"
      :loading="loading"
      @submit="handleLogin"
    />
  </div>
</template>

<style scoped>
.login-view {
  display: flex;
  min-height: 100vh;
  background: var(--bg-primary, #f5f5f7);
}
</style>
