<script setup>
import { watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useConversationStore } from '@/stores/conversation'
import { useAgentStore } from '@/stores/agent'
import { NAvatar, NTag, NBadge, NInput, NButton, NDropdown, NSpace, NSpin, NIcon } from 'naive-ui'
import { ChevronBackOutline } from '@vicons/ionicons5'
import { createDirectConversation } from '@/api/conversations'
import AgentSelector from '@/components/agent/AgentSelector.vue'
import { useAgentSelector } from '@/composables/useAgentSelector'

const router = useRouter()
const emit = defineEmits(['collapse'])
const convStore = useConversationStore()
const agentStore = useAgentStore()

const { showAgentSelector, selectorMode, openAgentSelector } = useAgentSelector()

onMounted(async () => {
  await Promise.all([convStore.loadList(), agentStore.loadAgents()])
})

watch(() => convStore.filter, () => {
  convStore.loadList()
})

function selectConversation(idOrConv) {
  const id = typeof idOrConv === 'object' ? (idOrConv.conversationId || idOrConv.id) : idOrConv
  convStore.setActive(id)
  if (typeof idOrConv === 'object' && idOrConv.conversationType === 'direct' && idOrConv.agentId) {
    agentStore.selectAgent(idOrConv.agentId)
  }
  router.push(`/chat/${id}`)
}

async function handleCreateConversation(config) {
  console.log('[Sidebar] handleCreateConversation config:', config)
  showAgentSelector.value = false
  if (config.mode === 'direct') {
    try {
      const result = await createDirectConversation({ agentId: config.agentId, title: config.topic })
      if (result?.conversationId) {
        agentStore.selectAgent(config.agentId)
        await convStore.loadList()
        convStore.setActive(result.conversationId)
        router.push(`/chat/${result.conversationId}`)
      }
    } catch (err) {
      console.warn('Failed to create direct conversation:', err)
    }
  } else {
    try {
      const result = await convStore.createGroup({
        title: config.title,
        agentIds: config.agentIds,
        schedulingMode: config.schedulingMode,
        failurePolicy: config.failurePolicy,
        maxParallelTasks: config.maxParallelTasks
      })
      if (result?.conversationId) {
        convStore.cacheDetail(result.conversationId, result)
        convStore.setActive(result.conversationId)
        router.push(`/chat/${result.conversationId}`)
      }
    } catch (err) {
      console.warn('Failed to create group:', err)
    }
  }
}

function handlePin(id) {
  convStore.togglePin(id)
}

function handleArchive(id) {
  convStore.toggleArchive(id)
}

function handleDelete(id) {
  convStore.deleteConversation(id)
}

function formatTime(ts) {
  if (!ts) return ''
  const d = new Date(ts)
  const now = new Date()
  const diffMs = now - d
  if (diffMs < 60000) return '刚刚'
  if (diffMs < 3600000) return `${Math.floor(diffMs / 60000)}分钟前`
  if (diffMs < 86400000) return `${Math.floor(diffMs / 3600000)}小时前`
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function handleScroll(e) {
  const { scrollHeight, scrollTop, clientHeight } = e.target
  if (scrollHeight - scrollTop - clientHeight < 40) {
    convStore.loadMore()
  }
}

function contextMenuOptions(conv) {
  return [
    { label: conv.pinnedAt ? '取消置顶' : '置顶', key: 'pin' },
    { label: conv.archived ? '取消归档' : '归档', key: 'archive' },
    { label: '删除', key: 'delete' }
  ]
}

function handleContextMenu(key, conv) {
  if (key === 'pin') handlePin(conv.conversationId)
  else if (key === 'archive') handleArchive(conv.conversationId)
  else if (key === 'delete') handleDelete(conv.conversationId)
}
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <NSpace vertical :size="8" style="width: 100%">
        <div class="type-filter">
          <NButton
            v-for="opt in [
              { label: '全部', value: 'all' },
              { label: '单聊', value: 'direct' },
              { label: '群聊', value: 'group' }
            ]"
            :key="opt.value"
            size="small"
            :type="convStore.filter === opt.value ? 'primary' : 'default'"
            :ghost="convStore.filter !== opt.value"
            @click="convStore.filter = opt.value"
          >
            {{ opt.label }}
          </NButton>
        </div>
        <NInput
          v-model:value="convStore.searchKeyword"
          placeholder="搜索对话..."
          clearable
          size="small"
        />
        <NButton text size="small" @click="emit('collapse')" class="collapse-btn" title="收起侧边栏">
          <NIcon :component="ChevronBackOutline" size="16" />
        </NButton>
      </NSpace>
    </div>

    <div class="sidebar-list" @scroll="handleScroll">
      <NSpin :show="convStore.loading">
        <div
          v-for="conv in convStore.filteredConversations"
          :key="conv.id"
          class="conv-item"
          :class="{
            active: conv.conversationId === convStore.activeId || String(conv.id) === String(convStore.activeId),
            pinned: conv.pinnedAt
          }"
          @click="selectConversation(conv)"
        >
          <div class="conv-content">
            <NAvatar v-if="conv.agentAvatarUrl" :size="40" :src="conv.agentAvatarUrl" round>
              <template #fallback>
                {{ (conv.title || conv.agentName || '?')[0] }}
              </template>
            </NAvatar>
            <NAvatar v-else :size="40" round>
              {{ (conv.title || conv.agentName || '?')[0] }}
            </NAvatar>
            <div class="conv-info">
              <div class="conv-top">
                <span class="conv-title">
                  {{ conv.title || conv.agentName || '未命名对话' }}
                </span>
                <span class="conv-time">{{ formatTime(conv.lastActiveAt) }}</span>
              </div>
              <div class="conv-bottom">
                <span class="conv-preview">{{ conv.lastMessagePreview || '暂无消息' }}</span>
                <NBadge
                  v-if="conv.unreadCount > 0"
                  :value="conv.unreadCount"
                  :max="99"
                  type="error"
                  size="tiny"
                />
                <NTag
                  v-if="conv.conversationType === 'group'"
                  size="tiny"
                  :bordered="false"
                >
                  群聊
                </NTag>
              </div>
            </div>
            <NDropdown
              trigger="click"
              :options="contextMenuOptions(conv)"
              @select="(key) => handleContextMenu(key, conv)"
            >
              <NButton text size="tiny" @click.stop class="more-btn">⋮</NButton>
            </NDropdown>
          </div>
        </div>

        <div v-if="!convStore.loading && convStore.filteredConversations.length === 0" class="empty-list">
          暂无对话
        </div>
        <div v-if="convStore.loadingMore" style="text-align:center;padding:12px">
          <NSpin size="small" />
        </div>
        <div v-else-if="!convStore.hasMore && convStore.filteredConversations.length > 0" style="text-align:center;padding:12px;color:#999;font-size:12px">
          已加载全部
        </div>
      </NSpin>
    </div>

    <AgentSelector
      :show="showAgentSelector"
      :agents="agentStore.agents"
      :mode="selectorMode"
      @close="showAgentSelector = false"
      @create="handleCreateConversation"
    />
  </aside>
</template>

<style scoped>
.sidebar {
  width: 320px;
  min-width: 260px;
  max-width: 400px;
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #F5F5F7;
  border-right: 1px solid #E5E5EA;
  overflow: hidden;
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid #E5E5EA;
}

.sidebar-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.conv-item {
  padding: 10px 12px;
  border-radius: 14px;
  cursor: pointer;
  transition: background 0.15s;
  margin-bottom: 2px;
}

.conv-item:hover {
  background: rgba(0,0,0,0.04);
}

.conv-item.active {
  background: rgba(46,117,182,0.1);
}

.conv-content {
  display: flex;
  align-items: center;
  gap: 12px;
}

.conv-info {
  flex: 1;
  min-width: 0;
}

.conv-top {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 2px;
}

.conv-title {
  font-size: 16px;
  font-weight: 500;
  color: #1D1D1F;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  font-size: 12px;
  color: #999;
  flex-shrink: 0;
  margin-left: 8px;
}

.conv-bottom {
  display: flex;
  align-items: center;
  gap: 8px;
}

.conv-preview {
  font-size: 14px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.more-btn {
  opacity: 0;
  transition: opacity 0.15s;
}

.conv-item:hover .more-btn {
  opacity: 1;
}

.type-filter {
  display: flex;
  gap: 4px;
  width: 100%;
}

.type-filter :deep(.n-button) {
  flex: 1;
  border-radius: 8px;
}

.collapse-btn {
  align-self: flex-end;
  color: #999;
  margin-top: 4px;
}
.collapse-btn:hover {
  color: #666;
}

.empty-list {
  text-align: center;
  color: #999;
  padding: 40px 0;
  font-size: 14px;
}
</style>
