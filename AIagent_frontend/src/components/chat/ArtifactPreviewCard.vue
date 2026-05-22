<script setup>
import { NButton, NSpace } from 'naive-ui'

const props = defineProps({
  message: { type: Object, required: true },
  artifact: { type: Object, default: null }
})

const emit = defineEmits(['preview', 'edit', 'deploy', 'download'])
</script>

<template>
  <div class="artifact-preview-card">
    <div class="artifact-header">
      <span class="artifact-name">📄 {{ artifact?.artifactName || '产物' }}</span>
      <span v-if="artifact?.currentVersion" class="artifact-version">v{{ artifact.currentVersion }}</span>
    </div>
    <div v-if="artifact?.artifactType === 'html'" class="artifact-preview">
      <iframe
        v-if="artifact.filePath"
        :src="artifact.filePath"
        sandbox="allow-scripts"
        class="preview-frame"
      />
    </div>
    <NSpace class="artifact-actions">
      <NButton size="small" @click="emit('preview', artifact?.id)">预览</NButton>
      <NButton size="small" @click="emit('edit', artifact?.id)">编辑</NButton>
      <NButton size="small" type="primary" @click="emit('deploy', artifact?.id)">部署</NButton>
      <NButton size="small" @click="emit('download', artifact?.id)">下载</NButton>
    </NSpace>
  </div>
</template>

<style scoped>
.artifact-preview-card {
  background: #FFFFFF;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.artifact-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  background: #F5F5F7;
  border-bottom: 1px solid #E5E5EA;
}

.artifact-name {
  font-size: 14px;
  font-weight: 500;
}

.artifact-version {
  font-size: 12px;
  color: #999;
}

.preview-frame {
  width: 100%;
  min-height: 240px;
  border: none;
}

.artifact-actions {
  padding: 8px 16px;
}
</style>
