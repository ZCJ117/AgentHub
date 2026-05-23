<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import BrandMark from '@/components/common/BrandMark.vue'
import UserPill from './UserPill.vue'
import { BusinessOutline, ChevronDownOutline } from '@vicons/ionicons5'
import { NButton, NDropdown, NIcon } from 'naive-ui'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

const navItems = [
  { key: 'agents',  label: 'Agent',  path: '/agents' },
  { key: 'artifacts', label: '产物',  path: '/artifacts' },
  { key: 'settings',  label: '设置',  path: '/settings' }
]

const activeKey = computed(() => {
  const name = route.name
  if (name === 'Agents' || name === 'AgentDetail') return 'agents'
  if (name === 'Artifacts' || name === 'ArtifactDetail') return 'artifacts'
  if (name === 'Settings') return 'settings'
  return ''
})

function navigate(item) {
  router.push(item.path)
}

function handleLogout() {
  authStore.logout()
  router.replace({ name: 'Login' })
}

const workspaceOptions = computed(() =>
  workspaceStore.workspaces.map(w => ({
    label: w.name,
    value: w.id
  }))
)

function handleSwitchWorkspace(id) {
  workspaceStore.selectWorkspace(id)
}
</script>

<template>
  <header class="topbar">
    <div class="topbar-inner">
      <BrandMark size="sm" />

      <nav class="nav-segment">
        <button
          v-for="item in navItems"
          :key="item.key"
          class="nav-pill"
          :class="{ active: activeKey === item.key }"
          @click="navigate(item)"
        >
          {{ item.label }}
        </button>
      </nav>

      <div class="spacer" />

      <n-dropdown
        trigger="click"
        :options="workspaceOptions"
        @select="handleSwitchWorkspace"
      >
        <n-button text class="workspace-switcher">
          <template #icon>
            <n-icon :component="BusinessOutline" />
          </template>
          {{ workspaceStore.activeWorkspace?.name || '选择工作区' }}
          <n-icon :component="ChevronDownOutline" />
        </n-button>
      </n-dropdown>

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
}

.topbar-inner {
  margin: 0 auto;
  padding: 8px 16px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.nav-segment {
  display: flex;
  align-items: center;
  gap: 4px;
  background: rgba(0, 0, 0, 0.04);
  border-radius: 10px;
  padding: 3px;
}

.nav-pill {
  padding: 6px 16px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-family: inherit;
  font-size: 13px;
  font-weight: 500;
  color: #666;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  white-space: nowrap;
  outline: none;
  line-height: 1.4;
}

.nav-pill:hover {
  color: #1D1D1F;
  background: rgba(0, 0, 0, 0.04);
}

.nav-pill.active {
  background: #FFFFFF;
  color: #1D1D1F;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08), 0 1px 2px rgba(0, 0, 0, 0.04);
  font-weight: 600;
}

.spacer {
  flex: 1 1 auto;
}

.workspace-switcher {
  font-size: 13px;
  color: #666;
  background: #F5F5F7;
  padding: 4px 10px;
  border-radius: 8px;
  white-space: nowrap;
  gap: 6px;
}
.workspace-switcher:hover {
  background: #E8E8ED;
}
</style>
