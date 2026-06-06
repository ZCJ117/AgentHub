<script setup>
import { NButton, NSpace, NCode } from 'naive-ui'
import { renderMarkdown } from '@/composables/useMarkdown'
import { computed } from 'vue'

const props = defineProps({
  message: { type: Object, required: true },
  artifact: { type: Object, default: null }
})

const emit = defineEmits(['preview', 'edit', 'deploy', 'download'])

// Fall back to message.content when artifact is missing (page refresh clears Pinia store)
const displayContent = computed(() =>
  props.artifact?.content || props.message?.content || ''
)
const displayName = computed(() =>
  props.artifact?.artifactName || '产物'
)
const displayType = computed(() => {
  if (props.artifact?.artifactType) return props.artifact.artifactType
  // Infer from message content when artifact is missing
  const name = displayName.value.toLowerCase()
  if (name.endsWith('.md')) return 'document'
  if (name.endsWith('.html') || name.endsWith('.htm')) return 'website'
  if (name.endsWith('.css') || name.endsWith('.scss') || name.endsWith('.less')) return 'stylesheet'
  return 'code'
})

const isEphemeral = computed(() => !props.artifact || !!props.artifact?.content)

const showCodeBlock = computed(() => {
  const t = displayType.value
  return t === 'website' || t === 'code' || t === 'data' || t === 'stylesheet'
})

const showDocument = computed(() => displayType.value === 'document')

const codeLanguage = computed(() => {
  const name = displayName.value.toLowerCase()
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

const renderedMd = computed(() => renderMarkdown(displayContent.value))

function handlePreview() {
  if (isEphemeral.value) {
    const content = displayContent.value
    const name = displayName.value
    const blob = new Blob([content], { type: name.endsWith('.md') ? 'text/markdown' : 'text/html' })
    const url = URL.createObjectURL(blob)
    window.open(url, '_blank')
  } else {
    emit('preview', props.artifact?.id)
  }
}

function handleDownload() {
  if (isEphemeral.value) {
    const content = displayContent.value
    const name = displayName.value
    const blob = new Blob([content], { type: 'text/plain' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.click()
    URL.revokeObjectURL(url)
  } else {
    emit('download', props.artifact?.id)
  }
}
</script>

<template>
  <div class="artifact-preview-card">
    <div class="artifact-header">
      <span class="artifact-name">{{ displayName }}</span>
    </div>

    <!-- code/website/data/stylesheet: NCode 代码块 -->
    <div v-if="showCodeBlock && displayContent" class="artifact-code">
      <NCode :code="displayContent" :language="codeLanguage" />
    </div>

    <!-- document: Markdown 渲染 -->
    <div v-if="showDocument && displayContent" class="artifact-markdown">
      <div class="markdown-body" v-html="renderedMd" />
    </div>

    <NSpace class="artifact-actions">
      <NButton size="small" @click="handlePreview">{{ isEphemeral ? '在新标签页打开' : '预览' }}</NButton>
      <NButton v-if="!isEphemeral" size="small" @click="emit('edit', artifact?.id)">编辑</NButton>
      <NButton v-if="!isEphemeral" size="small" type="primary" @click="emit('deploy', artifact?.id)">部署</NButton>
      <NButton size="small" @click="handleDownload">下载</NButton>
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
