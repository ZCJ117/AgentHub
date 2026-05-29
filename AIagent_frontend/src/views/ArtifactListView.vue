<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useArtifactStore } from '@/stores/artifact'
import { NCard, NTag, NButton, NInput, NTabs, NTabPane, NSpin, NSpace, NGi, NGrid } from 'naive-ui'

const router = useRouter()
const store = useArtifactStore()

const typeFilter = ref('')
const searchKeyword = ref('')

onMounted(() => {
  store.loadList()
})

function openDetail(id) {
  router.push(`/artifacts/${id}`)
}

function typeIcon(type) {
  const icons = {
    html: '\u{1F310}', code: '\u{1F4BB}', markdown: '\u{1F4DD}',
    pdf: '\u{1F4D5}', ppt: '\u{1F4CA}', image: '\u{1F5BC}', other: '\u{1F4CE}'
  }
  return icons[type] || '\u{1F4CE}'
}

function statusBadge(status) {
  const map = {
    deployed: 'success', deploying: 'info',
    failed: 'error', none: 'default'
  }
  return map[status] || 'default'
}

const filteredArtifacts = computed(() => {
  let list = store.artifacts
  if (typeFilter.value) list = list.filter(a => a.artifactType === typeFilter.value)
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(a => a.artifactName.toLowerCase().includes(kw))
  }
  return list
})

const types = ['html', 'code', 'markdown', 'pdf', 'ppt', 'image', 'other']
</script>

<template>
  <div class="artifact-list-view">
    <div class="page-header">
      <NButton text @click="router.push('/chat')" style="font-size:18px">&larr; 返回</NButton>
      <h2>产物库</h2>
      <NInput v-model:value="searchKeyword" placeholder="搜索产物..." clearable style="width: 240px" />
    </div>

    <NTabs v-model:value="typeFilter" type="line">
      <NTabPane name="" tab="全部" />
      <NTabPane v-for="t in types" :key="t" :name="t" :tab="t" />
    </NTabs>

    <NSpin :show="store.loading">
      <NGrid v-if="filteredArtifacts.length > 0" :cols="3" :x-gap="16" :y-gap="16">
        <NGi v-for="a in filteredArtifacts" :key="a.id">
          <NCard hoverable @click="openDetail(a.id)" class="artifact-card">
            <div class="card-icon">{{ typeIcon(a.artifactType) }}</div>
            <div class="card-name">{{ a.artifactName }}</div>
            <NSpace>
              <NTag size="tiny">{{ a.artifactType }}</NTag>
              <NTag size="tiny" :type="statusBadge(a.deployStatus)">
                {{ a.deployStatus === 'deployed' ? '已部署' : a.deployStatus }}
              </NTag>
            </NSpace>
            <div class="card-version">v{{ a.currentVersion }} &middot; {{ a.updatedAt?.slice(0, 10) }}</div>
          </NCard>
        </NGi>
      </NGrid>
      <div v-else class="empty-state">暂无产物</div>
    </NSpin>
  </div>
</template>

<style scoped>
.artifact-list-view { padding: 24px; max-width: 1200px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-header h2 { font-size: 22px; font-weight: 600; }
.artifact-card { cursor: pointer; border-radius: 14px; }
.card-icon { font-size: 32px; margin-bottom: 8px; }
.card-name { font-size: 14px; font-weight: 500; margin-bottom: 8px; }
.card-version { font-size: 12px; color: #999; margin-top: 4px; }
.empty-state { text-align: center; color: #999; padding: 60px 0; }
</style>
