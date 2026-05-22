<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import BrandMark from '@/components/common/BrandMark.vue'
import UserPill from './UserPill.vue'
import { NButton, NSpace } from 'naive-ui'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

function handleLogout() {
  authStore.logout()
  router.replace({ name: 'Login' })
}
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <BrandMark size="sm" />
      <NSpace :size="4">
        <NButton text size="small" @click="router.push('/chat')">对话</NButton>
        <NButton text size="small" @click="router.push('/agents')">Agent</NButton>
        <NButton text size="small" @click="router.push('/artifacts')">产物</NButton>
        <NButton text size="small" @click="router.push('/settings')">设置</NButton>
      </NSpace>
      <div class="spacer" />
      <span v-if="workspaceStore.activeWorkspace" class="ws-badge">
        {{ workspaceStore.activeWorkspace.name || '工作区' }}
      </span>
      <UserPill
        :username="authStore.nickname || authStore.username || '-'"
        :meta="authStore.role || '-'"
      />
      <NButton text size="small" @click="handleLogout">退出</NButton>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: 10;
  background: linear-gradient(180deg, rgba(245, 245, 247, 0.97), rgba(245, 245, 247, 0.82));
  backdrop-filter: blur(18px) saturate(180%);
  -webkit-backdrop-filter: blur(18px) saturate(180%);
  border-bottom: 1px solid #E5E5EA;
  transition: border-color 0.3s ease;
}

.topbar-inner {
  margin: 0 auto;
  padding: 8px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.spacer {
  flex: 1 1 auto;
}

.ws-badge {
  font-size: 12px;
  color: #666;
  background: #F5F5F7;
  padding: 4px 10px;
  border-radius: 8px;
  white-space: nowrap;
}
</style>
