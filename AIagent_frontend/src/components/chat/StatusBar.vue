<script setup>
defineProps({
  isConnected: { type: Boolean, default: true },
  isStreaming: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
</script>

<template>
  <div v-if="!isConnected || error" class="status-bar" :class="{ error: !!error }">
    <span v-if="error" class="status-text">{{ error }}</span>
    <span v-else-if="!isConnected" class="status-text">连接已断开，正在重连...</span>
  </div>
</template>

<style scoped>
.status-bar {
  padding: 6px 16px;
  background: #FFF3CD;
  color: #856404;
  text-align: center;
  font-size: 12px;
}

.status-bar.error {
  background: #FFEBEE;
  color: #FF3B30;
}
</style>
