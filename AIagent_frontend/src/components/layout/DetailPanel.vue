<script setup>
import { computed, inject } from 'vue'
import { useConversationStore } from '@/stores/conversation'
import { useOrchestratorStore } from '@/stores/orchestrator'
import { useChatStore } from '@/stores/chat'
import { NAvatar, NTag, NButton, NCollapse, NCollapseItem, NSpin, NSpace } from 'naive-ui'

const convStore = useConversationStore()
const orchStore = useOrchestratorStore()
const chatStore = useChatStore()

const emit = defineEmits(['unpinMessage'])
const scrollToMessage = inject('scrollToMessage', null)

const conversation = computed(() => convStore.activeConversation)
const pinnedMessages = computed(() => convStore.pinnedMessages || [])

const enrichedPins = computed(() =>
  pinnedMessages.value.map(pin => {
    const msg = chatStore.messages.find(m => m.id === pin.messageId)
    return {
      ...pin,
      messagePreview: msg ? (msg.content || '').slice(0, 80) : ''
    }
  })
)
const isGroup = computed(() => conversation.value?.conversationType === 'group')
const task = computed(() => orchStore.currentTask)
const assignments = computed(() => orchStore.assignments)

function statusIcon(status) {
  const map = { completed: '✅', running: '⏳', failed: '❌', pending: '⏸', cancelled: '⏸' }
  return map[status] || '⏸'
}

function parseMemberTags(tags) {
  if (Array.isArray(tags)) return tags
  if (typeof tags === 'string') {
    try { return JSON.parse(tags) } catch { return [] }
  }
  return []
}
</script>

<template>
  <aside class="detail-panel" v-if="conversation">
    <div class="panel-section">
      <h4>对话信息</h4>
      <div class="info-row">
        <span class="label">类型</span>
        <NTag size="tiny" :bordered="false">
          {{ isGroup ? '群聊' : '单聊' }}
        </NTag>
      </div>
      <div class="info-row" v-if="conversation.agentName">
        <span class="label">Agent</span>
        <span>{{ conversation.agentName }}</span>
      </div>
      <div class="info-row">
        <span class="label">消息数</span>
        <span>{{ conversation.messageCount || 0 }}</span>
      </div>
    </div>

    <div class="panel-section" v-if="isGroup && conversation.members?.length">
      <h4>群聊成员 ({{ conversation.members.length }})</h4>
      <div
        v-for="member in conversation.members"
        :key="member.agentId"
        class="member-item"
      >
        <NAvatar v-if="member.avatarUrl" :size="32" round :src="member.avatarUrl">
          <template #fallback>
            {{ (member.agentName || '?')[0] }}
          </template>
        </NAvatar>
        <NAvatar v-else :size="32" round>
          {{ (member.agentName || '?')[0] }}
        </NAvatar>
        <div class="member-info">
          <span class="member-name">{{ member.agentName }}</span>
          <span class="member-desc" v-if="member.description">{{ member.description }}</span>
          <div class="member-tags" v-if="member.capabilityTags">
            <NTag v-for="tag in parseMemberTags(member.capabilityTags).slice(0, 3)" :key="tag" size="tiny" :bordered="false">
              {{ tag }}
            </NTag>
          </div>
        </div>
      </div>
    </div>

    <div class="panel-section" v-if="enrichedPins.length > 0">
      <h4>钉选消息 ({{ enrichedPins.length }})</h4>
      <div
        v-for="pin in enrichedPins"
        :key="pin.id"
        class="pinned-item"
        @click="scrollToMessage?.(pin.messageId)"
      >
        <p class="pin-note">{{ pin.note || '(无备注)' }}</p>
        <p class="pin-preview">{{ pin.messagePreview || '(无内容)' }}</p>
        <NButton
          size="tiny"
          text
          type="error"
          @click.stop="emit('unpinMessage', pin.messageId)"
        >
          取消
        </NButton>
      </div>
    </div>

    <!-- Orchestrator task status (group chat only) -->
    <div class="panel-section" v-if="isGroup && task">
      <h4>任务进度</h4>
      <div class="progress-bar">
        <div
          class="progress-fill"
          :style="{ width: orchStore.progressPercent + '%' }"
          :class="{ done: orchStore.progressPercent === 100 }"
        />
      </div>
      <span class="progress-text">{{ orchStore.progressPercent }}%</span>

      <div class="assignment-list" v-if="assignments.length > 0">
        <div v-for="a in assignments" :key="a.agentName" class="assignment-item">
          <span>{{ statusIcon(a.status) }}</span>
          <span class="assign-agent">{{ a.agentName }}</span>
          <span class="assign-status">{{ a.status }}</span>
        </div>
      </div>
    </div>

    <div class="panel-empty" v-if="!isGroup && !task">
      <p class="empty-hint">选择对话查看详情</p>
    </div>
  </aside>
</template>

<style scoped>
.detail-panel {
  width: 320px;
  height: 100vh;
  background: #F5F5F7;
  border-left: 1px solid #E5E5EA;
  overflow-y: auto;
  padding: 16px;
  flex-shrink: 0;
}

.panel-section {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #E5E5EA;
}

.panel-section h4 {
  font-size: 15px;
  font-weight: 600;
  color: #999;
  margin-bottom: 10px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.info-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 0;
  font-size: 14px;
}

.info-row .label {
  color: #999;
}

.progress-bar {
  height: 6px;
  background: #E5E5EA;
  border-radius: 3px;
  margin: 8px 0;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #2E75B6;
  border-radius: 3px;
  transition: width 0.3s;
}

.progress-fill.done {
  background: #34C759;
}

.progress-text {
  font-size: 13px;
  color: #666;
}

.assignment-list {
  margin-top: 10px;
}

.assignment-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  font-size: 14px;
}

.assign-agent {
  flex: 1;
  font-weight: 500;
}

.assign-status {
  color: #999;
}

.member-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 8px 0;
}

.member-info {
  flex: 1;
  min-width: 0;
}

.member-name {
  font-size: 14px;
  font-weight: 500;
  color: #1D1D1F;
  display: block;
}

.member-desc {
  font-size: 12px;
  color: #999;
  display: block;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.member-tags {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  margin-top: 4px;
}

.panel-empty {
  text-align: center;
  padding: 40px 0;
}

.empty-hint {
  font-size: 13px;
  color: #999;
}

.pinned-item {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #FFFFFF;
  border-radius: 12px;
  cursor: pointer;
  transition: box-shadow 0.15s;
}

.pinned-item:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.pin-note {
  font-size: 13px;
  font-weight: 500;
  color: #1D1D1F;
  margin: 0 0 4px 0;
}

.pin-preview {
  font-size: 12px;
  color: #999;
  margin: 0 0 8px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 240px;
}
</style>
