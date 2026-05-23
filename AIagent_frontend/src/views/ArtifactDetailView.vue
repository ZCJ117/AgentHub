<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useArtifactStore } from '@/stores/artifact'
import { renderMarkdown } from '@/composables/useMarkdown'
import { NButton, NTag, NCode, NSpin, NSpace, NTimeline, NTimelineItem, NModal, NSelect, NDynamicTags } from 'naive-ui'

const route = useRoute()
const router = useRouter()
const store = useArtifactStore()
const artifactId = computed(() => Number(route.params.id))

const selectedVersion1 = ref(null)
const selectedVersion2 = ref(null)
const showDiff = ref(false)
const deploying = ref(false)
const contentLoading = ref(false)

const renderedMarkdown = computed(() => renderMarkdown(store.current?.content || ''))

const codeLanguage = computed(() => {
  const ct = store.current?.contentType || ''
  const map = {
    'text/html': 'html',
    'text/css': 'css',
    'text/javascript': 'javascript',
    'application/javascript': 'javascript',
    'text/typescript': 'typescript',
    'text/x-python': 'python',
    'text/x-java': 'java',
    'text/x-go': 'go',
    'text/x-rust': 'rust',
    'application/json': 'json',
    'text/x-yaml': 'yaml',
    'text/markdown': 'markdown',
    'text/x-sh': 'bash',
    'text/x-sql': 'sql'
  }
  return map[ct] || ct.split('/').pop() || 'text'
})

const imageUrl = computed(() => store.current?.downloadUrl || store.current?.filePath || '')

onMounted(async () => {
  await store.loadDetail(artifactId.value)
  await store.loadVersions(artifactId.value)
  contentLoading.value = true
  await store.loadContent(artifactId.value)
  contentLoading.value = false
})

const tags = computed({
  get: () => store.current?.tags || [],
  set: (value) => store.updateTags(artifactId.value, value)
})

async function handleDeploy() {
  deploying.value = true
  try {
    await store.deploy(artifactId.value, { deployTarget: 'static_host' })
    await store.loadDeployHistory(artifactId.value)
  } finally {
    deploying.value = false
  }
}

async function handleRestore(versionId) {
  await store.restoreVersion(artifactId.value, versionId)
}

async function handleDiff() {
  if (selectedVersion1.value && selectedVersion2.value) {
    await store.loadDiff(artifactId.value, selectedVersion1.value, selectedVersion2.value)
    showDiff.value = true
  }
}
</script>

<template>
  <div class="artifact-detail" v-if="store.current">
    <div class="detail-header">
      <NButton text @click="router.push('/artifacts')">&larr; 返回</NButton>
      <h2>{{ store.current.artifactName }}</h2>
      <NSpace>
        <NTag>{{ store.current.artifactType }}</NTag>
        <NTag :type="store.current.deployStatus === 'deployed' ? 'success' : 'default'">
          {{ store.current.deployStatus }}
        </NTag>
      </NSpace>
    </div>

    <div class="detail-body">
      <div class="detail-left">
        <h4>版本历史</h4>
        <NTimeline>
          <NTimelineItem
            v-for="v in store.versions"
            :key="v.id"
            :title="`v${v.versionNumber}`"
            :time="v.createdAt?.slice(0, 10)"
          >
            <div class="version-item">
              <span>{{ v.changeSummary || '无变更说明' }}</span>
              <NButton size="tiny" @click="handleRestore(v.id)">恢复</NButton>
            </div>
          </NTimelineItem>
        </NTimeline>
      </div>

      <div class="detail-center">
        <NSpin v-if="contentLoading" />

        <iframe v-else-if="store.current.artifactType === 'html' && store.current.content"
          :srcdoc="store.current.content" sandbox="allow-scripts" class="preview-full" />

        <NCode v-else-if="store.current.artifactType === 'code' && store.current.content"
          :code="store.current.content" :language="codeLanguage" />

        <div v-else-if="store.current.artifactType === 'markdown' && store.current.content"
          v-html="renderedMarkdown" class="markdown-preview" />

        <img v-else-if="store.current.artifactType === 'image' && imageUrl"
          :src="imageUrl" class="image-preview" />

        <div v-else class="generic-preview">文件类型: {{ store.current.artifactType }}</div>
      </div>

      <div class="detail-right">
        <div class="meta-section">
          <h4>信息</h4>
          <p>创建者: Agent #{{ store.current.creatorAgentId }}</p>
          <p>版本: v{{ store.current.currentVersion }}</p>
          <p v-if="store.current.deployUrl">
            部署地址: <a :href="store.current.deployUrl" target="_blank">{{ store.current.deployUrl }}</a>
          </p>
        </div>

        <div class="tags-section">
          <h4>标签</h4>
          <NDynamicTags v-model:value="tags" />
        </div>

        <NSpace vertical style="margin-top: 16px">
          <NButton type="primary" block @click="handleDeploy" :loading="deploying">
            部署
          </NButton>
          <div class="diff-selects">
            <NSelect
              v-model:value="selectedVersion1"
              :options="store.versions.map(v => ({ label: `v${v.versionNumber}`, value: v.versionNumber }))"
              placeholder="版本 A"
              size="small"
              clearable
            />
            <span class="vs-text">vs</span>
            <NSelect
              v-model:value="selectedVersion2"
              :options="store.versions.map(v => ({ label: `v${v.versionNumber}`, value: v.versionNumber }))"
              placeholder="版本 B"
              size="small"
              clearable
            />
          </div>
          <NButton block @click="handleDiff" :disabled="!selectedVersion1 || !selectedVersion2">
            对比版本
          </NButton>
        </NSpace>
      </div>
    </div>

    <NModal v-model:show="showDiff" title="版本对比">
      <div style="padding: 16px; background: #fff; border-radius: 14px; max-width: 800px">
        <NCode v-if="store.diffResult" :code="store.diffResult" language="diff" />
        <div v-else>无差异</div>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.artifact-detail { padding: 24px; max-width: 1400px; margin: 0 auto; height: 100vh; display: flex; flex-direction: column; }
.detail-header { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; flex-shrink: 0; }
.detail-header h2 { font-size: 20px; }
.detail-body { display: flex; gap: 24px; flex: 1; min-height: 0; }
.detail-left { width: 240px; overflow-y: auto; flex-shrink: 0; }
.detail-center { flex: 1; min-width: 0; overflow: auto; }
.detail-right { width: 240px; flex-shrink: 0; }
.version-item { display: flex; justify-content: space-between; align-items: center; }
.preview-full { width: 100%; height: 100%; border: 1px solid #E5E5EA; border-radius: 14px; }
.image-preview { max-width: 100%; border-radius: 14px; }
.meta-section p { font-size: 13px; color: #666; margin: 4px 0; }
.diff-selects { display: flex; align-items: center; gap: 8px; }
.diff-selects .vs-text { font-size: 12px; color: #999; flex-shrink: 0; }
</style>
