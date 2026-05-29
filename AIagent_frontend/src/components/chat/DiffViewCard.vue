<script setup>
import { NCode, NButton, NSpace } from 'naive-ui'

defineProps({
  message: { type: Object, required: true }
})

const emit = defineEmits(['apply', 'reject'])
</script>

<template>
  <div class="diff-card">
    <div class="diff-header">
      <span class="diff-label">代码变更 (Diff)</span>
      <NSpace>
        <NButton size="small" type="success" @click="emit('apply', message.id)">应用 (Apply)</NButton>
        <NButton size="small" @click="emit('reject', message.id)">拒绝</NButton>
      </NSpace>
    </div>
    <NCode :code="String(message.content || '')" language="diff" />
  </div>
</template>

<style scoped>
.diff-card {
  background: #FFFFFF;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.diff-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background: #F5F5F7;
  border-bottom: 1px solid #E5E5EA;
}

.diff-label {
  font-size: 13px;
  font-weight: 500;
  color: #1D1D1F;
}
</style>
