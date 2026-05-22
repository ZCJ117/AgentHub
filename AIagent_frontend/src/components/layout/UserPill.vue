<script setup>
import { useAuthStore } from '@/stores/auth'
import { NAvatar } from 'naive-ui'

const authStore = useAuthStore()
</script>

<template>
  <div class="user-pill">
    <NAvatar :size="24" round>
      {{ (authStore.nickname || authStore.username || 'U')[0] }}
    </NAvatar>
    <strong>{{ authStore.nickname || authStore.username || '-' }}</strong>
    <span class="role-tag">{{ authStore.role || '-' }}</span>
  </div>
</template>

<style scoped>
.user-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 999px;
  background: #FFFFFF;
  border: 1px solid #E5E5EA;
  font-size: 12px;
  color: #666;
  white-space: nowrap;
  flex-shrink: 0;
}

.user-pill strong {
  font-weight: 600;
  color: #1D1D1F;
}

.role-tag {
  font-size: 11px;
  color: #999;
}
</style>
