<script setup>
import { ref, computed } from 'vue'
import { NModal, NAvatar, NTag, NCheckbox, NButton, NInput, NSelect, NInputNumber, NSpace } from 'naive-ui'

const props = defineProps({
  show: { type: Boolean, default: false },
  agents: { type: Array, default: () => [] },
  mode: { type: String, default: 'direct' } // 'direct' | 'group'
})

const emit = defineEmits(['close', 'create'])

const selectedAgentId = ref(null)
const selectedAgentIds = ref([])
const groupTitle = ref('')
const orchestratorAgentId = ref(null)
const schedulingMode = ref('auto')
const failurePolicy = ref('fail_tolerant')
const maxParallelTasks = ref(4)

const availableOrchestrators = computed(() =>
  props.agents.filter(a => a.agentType === 'orchestrator')
)

const availableMembers = computed(() =>
  props.agents.filter(a => a.agentType !== 'orchestrator' && a.enabled !== false)
)

function handleCreate() {
  if (props.mode === 'direct') {
    emit('create', { agentId: selectedAgentId.value, mode: 'direct' })
  } else {
    emit('create', {
      mode: 'group',
      title: groupTitle.value,
      agentIds: selectedAgentIds.value,
      orchestratorAgentId: orchestratorAgentId.value || (availableOrchestrators.value[0]?.id),
      schedulingMode: schedulingMode.value,
      failurePolicy: failurePolicy.value,
      maxParallelTasks: maxParallelTasks.value
    })
  }
}

function close() {
  selectedAgentId.value = null
  selectedAgentIds.value = []
  groupTitle.value = ''
  emit('close')
}
</script>

<template>
  <NModal :show="show" @update:show="close">
    <div class="agent-selector">
      <h3 class="selector-title">
        {{ mode === 'direct' ? '选择 AI Agent' : '创建群聊' }}
      </h3>

      <!-- Direct mode: single agent selection -->
      <div v-if="mode === 'direct'" class="agent-list">
        <div
          v-for="agent in availableMembers"
          :key="agent.id"
          class="agent-option"
          :class="{ selected: selectedAgentId === agent.id }"
          @click="selectedAgentId = agent.id"
        >
          <NAvatar :size="36" round :src="agent.avatarUrl">
            {{ (agent.name || 'AI')[0] }}
          </NAvatar>
          <div class="agent-info">
            <span class="agent-name">{{ agent.name }}</span>
            <span class="agent-desc">{{ agent.description || '' }}</span>
          </div>
          <NTag v-for="tag in (agent.capabilityTags || [])" :key="tag" size="tiny" :bordered="false">
            {{ tag }}
          </NTag>
        </div>
      </div>

      <!-- Group mode: multi-agent + config -->
      <div v-else class="group-form">
        <NInput v-model:value="groupTitle" placeholder="群聊名称" style="margin-bottom: 12px" />

        <div class="agent-check-list">
          <div v-for="agent in availableMembers" :key="agent.id" class="agent-check-item">
            <NCheckbox
              :checked="selectedAgentIds.includes(agent.id)"
              @update:checked="(checked) => {
                if (checked) selectedAgentIds.push(agent.id)
                else selectedAgentIds = selectedAgentIds.filter(id => id !== agent.id)
              }"
            />
            <NAvatar :size="28" round :src="agent.avatarUrl">
              {{ (agent.name || 'AI')[0] }}
            </NAvatar>
            <span class="agent-name">{{ agent.name }}</span>
            <NTag v-for="tag in (agent.capabilityTags || []).slice(0, 2)" :key="tag" size="tiny" :bordered="false">
              {{ tag }}
            </NTag>
          </div>
        </div>

        <div class="group-config" style="margin-top: 12px">
          <NSpace vertical :size="8">
            <NSelect
              v-model:value="orchestratorAgentId"
              :options="availableOrchestrators.map(a => ({ label: a.name, value: a.id }))"
              placeholder="选择 Orchestrator (可选)"
              clearable
            />
            <NSelect
              v-model:value="schedulingMode"
              :options="[
                { label: '自动分派', value: 'auto' },
                { label: '手动 @ 指定', value: 'manual' }
              ]"
            />
            <NSelect
              v-model:value="failurePolicy"
              :options="[
                { label: '失败继续', value: 'fail_tolerant' },
                { label: '失败立即停止', value: 'fail_fast' }
              ]"
            />
            <div style="display: flex; align-items: center; gap: 8px">
              <span style="font-size: 13px">最大并行任务</span>
              <NInputNumber v-model:value="maxParallelTasks" :min="1" :max="8" size="small" style="width: 80px" />
            </div>
          </NSpace>
        </div>
      </div>

      <NSpace justify="end" style="margin-top: 16px">
        <NButton @click="close">取消</NButton>
        <NButton
          type="primary"
          :disabled="mode === 'direct' ? !selectedAgentId : selectedAgentIds.length < 2"
          @click="handleCreate"
        >
          {{ mode === 'direct' ? '开始对话' : '创建群聊' }}
        </NButton>
      </NSpace>
    </div>
  </NModal>
</template>

<style scoped>
.agent-selector {
  background: #FFFFFF;
  border-radius: 20px;
  padding: 24px;
  width: 480px;
  max-height: 80vh;
  overflow-y: auto;
}

.selector-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 16px;
  color: #1D1D1F;
}

.agent-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.agent-option {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  cursor: pointer;
  transition: background 0.15s;
}

.agent-option:hover {
  background: #F5F5F7;
}

.agent-option.selected {
  background: rgba(46,117,182,0.1);
}

.agent-info {
  flex: 1;
  min-width: 0;
}

.agent-name {
  font-size: 14px;
  font-weight: 500;
  display: block;
}

.agent-desc {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.agent-check-list {
  max-height: 300px;
  overflow-y: auto;
}

.agent-check-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 0;
}
</style>
