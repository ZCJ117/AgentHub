<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { NAvatar, NTag, NButton, NInput, NTabs, NTabPane, NSpin, NSpace, NSwitch, NDynamicTags, NSelect, NIcon, NCheckboxGroup, NCheckbox, NEmpty, NGrid, NGridItem, NStatistic, NText } from 'naive-ui'
import { updateAgent, deleteAgent } from '@/api/agents'
import { CameraOutline } from '@vicons/ionicons5'
import { getToken } from '@/utils/token'
import { formatTime } from '@/utils/time'
import {
  fetchEnabledSkills,
  fetchAvailableTools,
  fetchAgentSkills,
  updateAgentSkills,
  fetchAgentTools,
  updateAgentTools,
  fetchAgentStats
} from '@/api/agent-bindings'

const route = useRoute()
const router = useRouter()
const store = useAgentStore()
const agentId = computed(() => route.params.id)

const activeTab = ref('config')
const saving = ref(false)

// Form state
const name = ref('')
const description = ref('')
const systemPrompt = ref('')
const agentType = ref('react')
const enabled = ref(true)
const isPublic = ref(true)
const capabilityTags = ref([])
const modelName = ref('')

// ── Skills tab state ──
const availableSkills = ref([])
const selectedSkillIds = ref([])
const loadingSkills = ref(false)
const savingSkills = ref(false)

// ── Tools tab state ──
const availableTools = ref([])
const selectedToolNames = ref([])
const loadingTools = ref(false)
const savingTools = ref(false)

// ── Stats tab state ──
const stats = ref(null)
const loadingStats = ref(false)

const agentTypes = [
  { label: 'ReAct', value: 'react' },
  { label: 'Plan-Execute', value: 'plan_execute' },
  { label: 'Orchestrator', value: 'orchestrator' }
]

async function loadSkillsTab() {
  if (agentId.value === 'new' || loadingSkills.value) return
  loadingSkills.value = true
  try {
    const [bindings, allSkills] = await Promise.all([
      fetchAgentSkills(Number(agentId.value)),
      fetchEnabledSkills()
    ])
    availableSkills.value = Array.isArray(allSkills) ? allSkills : (allSkills?.records || [])
    const boundIds = new Set((bindings || []).map(b => b.skillId))
    selectedSkillIds.value = availableSkills.value
      .filter(s => boundIds.has(s.id))
      .map(s => s.id)
  } finally {
    loadingSkills.value = false
  }
}

async function saveSkills() {
  if (agentId.value === 'new') return
  savingSkills.value = true
  try {
    await updateAgentSkills(Number(agentId.value), selectedSkillIds.value)
  } catch (err) {
    console.warn('Save skills failed:', err)
  } finally {
    savingSkills.value = false
  }
}

async function loadToolsTab() {
  if (agentId.value === 'new' || loadingTools.value) return
  loadingTools.value = true
  try {
    const [bindings, allTools] = await Promise.all([
      fetchAgentTools(Number(agentId.value)),
      fetchAvailableTools()
    ])
    availableTools.value = Array.isArray(allTools) ? allTools : (allTools?.records || [])
    const boundNames = new Set((bindings || []).map(b => b.toolName))
    selectedToolNames.value = availableTools.value
      .filter(t => boundNames.has(t.name))
      .map(t => t.name)
  } finally {
    loadingTools.value = false
  }
}

async function saveTools() {
  if (agentId.value === 'new') return
  savingTools.value = true
  try {
    await updateAgentTools(Number(agentId.value), selectedToolNames.value)
  } catch (err) {
    console.warn('Save tools failed:', err)
  } finally {
    savingTools.value = false
  }
}

async function loadStatsTab() {
  if (agentId.value === 'new' || loadingStats.value) return
  loadingStats.value = true
  try {
    stats.value = await fetchAgentStats(Number(agentId.value))
  } catch (err) {
    console.warn('Load stats failed:', err)
    stats.value = null
  } finally {
    loadingStats.value = false
  }
}

onMounted(async () => {
  if (agentId.value && agentId.value !== 'new') {
    const detail = await store.loadDetail(Number(agentId.value))
    if (detail) {
      name.value = detail.name || ''
      description.value = detail.description || ''
      systemPrompt.value = detail.systemPrompt || ''
      agentType.value = detail.agentType || 'react'
      enabled.value = detail.enabled !== false
      isPublic.value = detail.isPublic !== false
      capabilityTags.value = detail.capabilityTags || []
      modelName.value = detail.modelName || ''
    }
    // Lazy-load active tab
    if (activeTab.value === 'skills') loadSkillsTab()
    else if (activeTab.value === 'tools') loadToolsTab()
    else if (activeTab.value === 'stats') loadStatsTab()
  }
})

// Lazy-load tab content on switch
watch(activeTab, (tab) => {
  if (tab === 'skills') loadSkillsTab()
  else if (tab === 'tools') loadToolsTab()
  else if (tab === 'stats') loadStatsTab()
})

async function save() {
  saving.value = true
  try {
    const body = {
      name: name.value,
      description: description.value,
      systemPrompt: systemPrompt.value,
      agentType: agentType.value,
      enabled: enabled.value,
      isPublic: isPublic.value,
      capabilityTags: capabilityTags.value,
      modelName: modelName.value
    }
    if (agentId.value === 'new') {
      const { createAgent } = await import('@/api/agents')
      await createAgent(body)
    } else {
      await updateAgent(Number(agentId.value), body)
    }
    router.push('/agents')
  } catch (err) {
    console.warn('Save failed:', err)
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (agentId.value !== 'new' && confirm('确定删除此 Agent？')) {
    await deleteAgent(Number(agentId.value))
    router.push('/agents')
  }
}

const agentDetail = computed(() =>
  store.detailCache.get(Number(agentId.value)) || {}
)

const fileInput = ref(null)
const uploadMsg = ref('')

function triggerUpload() {
  fileInput.value?.click()
}

async function handleAvatarUpload(e) {
  const file = e.target.files[0]
  if (!file) return
  try {
    const formData = new FormData()
    formData.append('file', file)

    const token = getToken()
    const wsId = localStorage.getItem('ai_agent_workspace_id')
    const headers = { 'Authorization': `Bearer ${token}` }
    if (wsId) headers['X-Workspace-Id'] = wsId

    const res = await fetch(`/api/v1/agents/${agentId.value}/avatar`, {
      method: 'PUT',
      headers,
      body: formData
    })

    if (!res.ok) {
      const errData = await res.json().catch(() => ({}))
      throw new Error(errData.message || `上传失败 (${res.status})`)
    }

    store.detailCache.delete(Number(agentId.value))
    await store.loadDetail(Number(agentId.value))
  } catch (err) {
    uploadMsg.value = err.message || '头像上传失败'
    setTimeout(() => { uploadMsg.value = '' }, 3000)
  } finally {
    e.target.value = ''
  }
}
</script>

<template>
  <div class="agent-detail">
    <div class="detail-header">
      <NButton text @click="router.push('/agents')">← 返回</NButton>
      <h2>{{ agentId === 'new' ? '新建 Agent' : 'Agent 详情' }}</h2>
    </div>

    <div class="detail-body" v-if="agentId === 'new' || store.detailCache.has(Number(agentId))">
      <div class="detail-sidebar">
        <NAvatar v-if="agentId === 'new'" :size="80" round :src="null" class="avatar">
	          {{ (name || 'AI')[0] }}
	        </NAvatar>
	        <div v-else class="avatar-upload" @click="triggerUpload">
          <NAvatar :size="80" round :src="agentDetail.avatarUrl" :fallback="(agentDetail.name || 'AI')[0]" />
          <div class="avatar-overlay">
            <NIcon :component="CameraOutline" />
            <span>更换</span>
          </div>
          <input
            ref="fileInput"
            type="file"
            accept="image/*"
            style="display:none"
            @change="handleAvatarUpload"
          />
        </div>
        <span v-if="uploadMsg" style="color: #FF3B30; font-size: 12px; display: block; margin-top: 6px; text-align: center;">{{ uploadMsg }}</span>
	        <NInput v-model:value="name" placeholder="Agent 名称" class="name-input" />
        <NInput v-model:value="description" placeholder="简短描述" type="textarea" :rows="2" />
        <NSpace style="margin-top: 12px">
          <span>启用</span>
          <NSwitch v-model:value="enabled" />
        </NSpace>
        <NSpace>
          <span>公开</span>
          <NSwitch v-model:value="isPublic" />
        </NSpace>
      </div>

      <div class="detail-main">
        <NTabs v-model:value="activeTab">
          <NTabPane name="config" tab="配置">
            <div class="config-form">
              <NSpace vertical :size="12">
                <div>
                  <label>Agent 类型</label>
                  <NSelect v-model:value="agentType" :options="agentTypes" />
                </div>
                <div>
                  <label>模型</label>
                  <NInput v-model:value="modelName" placeholder="模型名称" />
                </div>
                <div>
                  <label>System Prompt</label>
                  <NInput v-model:value="systemPrompt" type="textarea"
                    placeholder="输入 System Prompt..." :rows="10"
                    style="font-family: monospace" />
                </div>
                <div>
                  <label>能力标签</label>
                  <NDynamicTags v-model:value="capabilityTags" />
                </div>
              </NSpace>
            </div>
          </NTabPane>
          <NTabPane name="skills" tab="技能">
            <p class="tab-placeholder">技能配置 — 开发中</p>
          </NTabPane>
          <NTabPane name="tools" tab="工具">
            <p class="tab-placeholder">工具配置 — 开发中</p>
          </NTabPane>
          <NTabPane name="stats" tab="统计">
            <p class="tab-placeholder">使用统计 — 开发中</p>
          </NTabPane>
        </NTabs>
      </div>
    </div>

    <div class="detail-footer">
      <NSpace>
        <NButton type="primary" @click="save" :loading="saving">保存</NButton>
        <NButton v-if="agentId !== 'new'" type="error" @click="remove">删除</NButton>
      </NSpace>
    </div>
  </div>
</template>

<style scoped>
.agent-detail { padding: 24px; max-width: 1000px; margin: 0 auto; }
.detail-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.detail-header h2 { font-size: 20px; }
.detail-body { display: flex; gap: 32px; }
.detail-sidebar { width: 220px; display: flex; flex-direction: column; gap: 12px; align-items: center; }
.detail-main { flex: 1; }
.config-form label { font-size: 13px; color: #666; display: block; margin-bottom: 4px; }
.detail-footer { margin-top: 24px; padding-top: 16px; border-top: 1px solid #E5E5EA; }
.tab-placeholder { color: #999; padding: 40px 0; }
.name-input { margin-top: 8px; }
.avatar-upload {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.2s;
  font-size: 12px;
  gap: 4px;
}
.avatar-upload:hover .avatar-overlay {
  opacity: 1;
}
</style>
