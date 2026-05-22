<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { NAvatar, NTag, NButton, NInput, NTabs, NTabPane, NSpin, NSpace, NSwitch, NDynamicTags, NSelect } from 'naive-ui'
import { updateAgent, deleteAgent } from '@/api/agents'

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

const agentTypes = [
  { label: 'ReAct', value: 'react' },
  { label: 'Plan-Execute', value: 'plan_execute' },
  { label: 'Orchestrator', value: 'orchestrator' }
]

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
  }
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
</script>

<template>
  <div class="agent-detail">
    <div class="detail-header">
      <NButton text @click="router.push('/agents')">← 返回</NButton>
      <h2>{{ agentId === 'new' ? '新建 Agent' : 'Agent 详情' }}</h2>
    </div>

    <div class="detail-body" v-if="agentId === 'new' || store.detailCache.has(Number(agentId)) || agentId === 'new'">
      <div class="detail-sidebar">
        <NAvatar :size="80" round :src="null" class="avatar">
          {{ (name || 'AI')[0] }}
        </NAvatar>
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
</style>
