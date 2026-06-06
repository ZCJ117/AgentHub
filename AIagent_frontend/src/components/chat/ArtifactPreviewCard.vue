<script setup>
import { NButton, NSpace, NCode } from 'naive-ui'
import { renderMarkdown } from '@/composables/useMarkdown'
import { computed } from 'vue'

const props = defineProps({
  message: { type: Object, required: true },
  artifact: { type: Object, default: null }
})

const emit = defineEmits(['preview', 'edit', 'deploy', 'download'])

const showCodeBlock = computed(() => {
  const t = props.artifact?.artifactType
  return t === 'website' || t === 'code' || t === 'data' || t === 'stylesheet'
})

const showDocument = computed(() => props.artifact?.artifactType === 'document')

const codeLanguage = computed(() => {
  const name = props.artifact?.artifactName || ''
  if (name.endsWith('.html') || name.endsWith('.htm')) return 'html'
  if (name.endsWith('.js')) return 'javascript'
  if (name.endsWith('.ts')) return 'typescript'
  if (name.endsWith('.vue')) return 'html'
  if (name.endsWith('.css')) return 'css'
  if (name.endsWith('.scss')) return 'scss'
  if (name.endsWith('.json')) return 'json'
  if (name.endsWith('.yaml') || name.endsWith('.yml')) return 'yaml'
  if (name.endsWith('.xml')) return 'xml'
  if (name.endsWith('.md')) return 'markdown'
  if (name.endsWith('.py')) return 'python'
  if (name.endsWith('.java')) return 'java'
  if (name.endsWith('.go')) return 'go'
  if (name.endsWith('.sql')) return 'sql'
  return 'text'
})

const renderedMd = computed(() => renderMarkdown(props.artifact?.content || ''))
</script>

<template>
  <div class="artifact-preview-card">
    <div class="artifact-header">
      <span class="artifact-name">{{ artifact?.artifactName || '产物' }}</span>
      <span v-if="artifact?.currentVersion" class="artifact-version">v{{ artifact.currentVersion }}</span>
    </div>

    <!-- code/website/data/stylesheet: NCode 代码块 -->
    <div v-if="showCodeBlock && artifact?.content" class="artifact-code">
      <NCode :code="artifact.content" :language="codeLanguage" />
    </div>

    <!-- document: Markdown 渲染 -->
    <div v-if="showDocument && artifact?.content" class="artifact-markdown">
      <div class="markdown-body" v-html="renderedMd" />
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

.artifact-actions {
  padding: 8px 16px;
}

.artifact-code {
  padding: 0;
  max-height: 400px;
  overflow: auto;
}

.artifact-markdown {
  padding: 12px 16px;
  font-size: 14px;
  line-height: 1.6;
  color: #1D1D1F;
}

.markdown-body :deep(pre) {
  background: #F5F5F7;
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
}

.markdown-body :deep(code) {
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
  font-size: 13px;
}
</style>
