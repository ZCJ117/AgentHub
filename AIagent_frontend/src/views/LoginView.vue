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
  <div class="login-page-wrapper">
    <main class="page">
      <HeroSection />
      <section class="login-panel" aria-label="登录">
        <LoginForm
          :error="error"
          :loading="loading"
          @submit="handleLogin"
        />
      </section>
    </main>
  </div>
</template>

<style scoped>
.login-page-wrapper {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f5f7;
  padding: 24px;
}

.page {
  width: min(1100px, 100%);
  display: grid;
  grid-template-columns: 1.22fr 0.78fr;
  gap: 40px;
  align-items: center;
}

.login-panel {
  display: flex;
  align-items: center;
}

@media (max-width: 767px) {
  .login-page-wrapper {
    padding: 16px;
    align-items: flex-start;
  }

  .page {
    grid-template-columns: 1fr;
    gap: 28px;
  }
}
</style>
