<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAgentStore } from '@/stores/agent'
import { NGrid, NGi, NCard, NAvatar, NTag, NButton, NInput, NTabs, NTabPane, NSpin, NBadge, NSpace } from 'naive-ui'

const router = useRouter()
const store = useAgentStore()
const searchKeyword = ref('')
const typeFilter = ref('')

const typeTabs = [
  { name: '', label: '全部' },
  { name: 'local_cli', label: '本地 CLI' },
  { name: 'react', label: 'ReAct' },
  { name: 'plan_execute', label: 'Plan-Execute' }
]

onMounted(() => {
  store.loadAgents()
})

const filteredAgents = computed(() => {
  let list = store.agents
  if (typeFilter.value) list = list.filter(a => a.agentType === typeFilter.value)
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(a => (a.name || '').toLowerCase().includes(kw))
  }
  return list
})

function statusColor(status) {
  return status === 'AVAILABLE' ? 'green' : status === 'BUSY' ? 'orange' : 'gray'
}

function parseTags(tags) {
  if (Array.isArray(tags)) return tags
  if (typeof tags === 'string') {
    try { return JSON.parse(tags) } catch { return [] }
  }
  return []
}
</script>

<template>
  <div class="agent-manage">
    <div class="page-header">
      <div class="header-left">
        <NButton text @click="router.push('/chat')" class="back-btn">← 返回主页</NButton>
        <h2>Agent 管理</h2>
      </div>
      <NSpace>
        <NInput v-model:value="searchKeyword" placeholder="搜索 Agent..." clearable style="width: 200px" />
        <NButton type="primary" @click="router.push('/agents/new')">+ 新建 Agent</NButton>
      </NSpace>
    </div>

    <NTabs v-model:value="typeFilter">
      <NTabPane v-for="t in typeTabs" :key="t.name" :name="t.name" :tab="t.label" />
    </NTabs>

    <NSpin :show="store.isLoading">
      <NGrid v-if="filteredAgents.length > 0" :cols="3" :x-gap="16" :y-gap="16">
        <NGi v-for="agent in filteredAgents" :key="agent.id">
          <NCard hoverable @click="router.push(`/agents/${agent.id}`)" class="agent-card">
            <div class="agent-card-header">
              <NAvatar :size="44" round :src="agent.avatarUrl">
                {{ (agent.name || 'AI')[0] }}
              </NAvatar>
              <NBadge :color="statusColor(agent.agentStatus)" dot>
                <div class="status-dot-placeholder" />
              </NBadge>
            </div>
            <div class="agent-card-body">
              <div class="agent-name">
                <span v-if="agent.agentType === 'local_cli'" style="margin-right:4px;font-size:14px">&#x1F5A5;</span>
                {{ agent.name }}
              </div>
              <div class="agent-desc">{{ agent.description || '暂无描述' }}</div>
              <div class="agent-tags">
                <NTag v-for="tag in parseTags(agent.capabilityTags)" :key="tag" size="tiny" :bordered="false">
                  {{ tag }}
                </NTag>
              </div>
            </div>
            <div class="agent-card-footer">
              <NSpace :size="6" align="center">
                <NTag v-if="agent.agentType === 'local_cli'" size="tiny" type="info">
                  {{ agent.cliType === 'claude_code' ? 'Claude Code' : agent.cliType === 'open_code' ? 'OpenCode' : agent.cliType || 'local_cli' }}
                </NTag>
                <NTag v-else size="tiny" :type="agent.agentType === 'react' ? 'default' : 'warning'">
                  {{ agent.agentType === 'react' ? 'ReAct' : agent.agentType === 'plan_execute' ? 'Plan-Execute' : agent.agentType || 'Agent' }}
                </NTag>
                <!-- Online status indicator -->
                <NTag :type="agent.agentStatus === 'AVAILABLE' ? 'success' : 'default'"
                      size="tiny" round>
                  {{ agent.agentStatus === 'AVAILABLE' ? '在线' : '离线' }}
                </NTag>
              </NSpace>
            </div>
          </NCard>
        </NGi>
      </NGrid>
      <div v-else class="empty-state">暂无 Agent</div>
    </NSpin>
  </div>
</template>

<style scoped>
.agent-manage { padding: 24px; max-width: 1200px; margin: 0 auto; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.header-left { display: flex; align-items: center; gap: 12px; }
.back-btn { font-size: 14px; color: #666; }
.page-header h2 { font-size: 22px; font-weight: 600; }
.agent-card { border-radius: 14px; cursor: pointer; }
.agent-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.agent-name { font-size: 15px; font-weight: 600; }
.agent-desc { font-size: 13px; color: #666; margin: 6px 0; }
.agent-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
.agent-card-footer { margin-top: 12px; padding-top: 8px; border-top: 1px solid #E5E5EA; }
.empty-state { text-align: center; color: #999; padding: 60px 0; }
.status-dot-placeholder { width: 12px; height: 12px; }
</style>
