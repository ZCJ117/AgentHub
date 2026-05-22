<script setup>
import { computed } from 'vue'
import { NProgress, NButton, NTag, NSpace } from 'naive-ui'

const props = defineProps({
  message: { type: Object, required: true },
  task: { type: Object, default: null },
  assignments: { type: Array, default: () => [] }
})

const emit = defineEmits(['cancel', 'retry'])

const planData = computed(() => {
  if (props.task?.planJson) {
    try {
      return typeof props.task.planJson === 'string'
        ? JSON.parse(props.task.planJson)
        : props.task.planJson
    } catch {
      return { steps: [] }
    }
  }
  return { steps: [] }
})

const steps = computed(() => planData.value.steps || [])

const progress = computed(() => {
  const total = steps.value.length
  if (total === 0) return 0
  const completed = steps.value.filter(s => s.status === 'completed').length
  return Math.round((completed / total) * 100)
})

const statusIcon = (status) => {
  switch (status) {
    case 'completed': return '✅'
    case 'running': return '⏳'
    case 'failed': return '❌'
    case 'cancelled': return '⏸'
    default: return '⏸'
  }
}

const statusText = (status) => {
  switch (status) {
    case 'completed': return '已完成'
    case 'running': return '进行中…'
    case 'failed': return '失败'
    case 'cancelled': return '已取消'
    default: return '等待中'
  }
}

const hasFailed = computed(() => steps.value.some(s => s.status === 'failed'))
const isActive = computed(() =>
  props.task?.status === 'running' || props.task?.status === 'pending'
)
</script>

<template>
  <div class="plan-card">
    <div class="plan-header">
      <span class="plan-icon">⚙</span>
      <span class="plan-title">Orchestrator · 任务拆解计划</span>
      <NTag v-if="props.task" size="small" :type="props.task.status === 'running' ? 'info' : 'default'">
        {{ props.task.title || '任务计划' }}
      </NTag>
    </div>

    <div class="plan-steps">
      <div
        v-for="step in steps"
        :key="step.order"
        class="step-item"
        :class="{ 'is-running': step.status === 'running', 'is-failed': step.status === 'failed' }"
      >
        <div class="step-indicator">
          <span v-if="step.dependsOn" class="step-dependency">↑ 依赖步骤 {{ step.dependsOn }}</span>
        </div>
        <div class="step-content">
          <span class="step-status">{{ statusIcon(step.status) }}</span>
          <span class="step-agent">{{ step.agentName }}</span>
          <span class="step-goal">{{ step.goal }}</span>
          <NTag size="tiny" :bordered="false">{{ step.mode === 'parallel' ? '并行' : '串行' }}</NTag>
        </div>
        <div class="step-status-text">{{ statusText(step.status) }}</div>
      </div>
    </div>

    <div class="plan-footer">
      <div class="progress-row">
        <span class="progress-label">进度</span>
        <NProgress
          type="line"
          :percentage="progress"
          :status="hasFailed ? 'error' : progress === 100 ? 'success' : 'default'"
          :height="8"
          :border-radius="4"
        />
        <span class="progress-text">{{ progress }}%</span>
      </div>

      <NSpace v-if="isActive" justify="end" style="margin-top: 8px">
        <NButton size="small" @click="emit('cancel')">取消</NButton>
        <NButton v-if="hasFailed" size="small" type="warning" @click="emit('retry')">
          重试失败项
        </NButton>
      </NSpace>
    </div>
  </div>
</template>

<style scoped>
.plan-card {
  background: #FFFFFF;
  border: 1px solid #E5E5EA;
  border-left: 3px solid #2E75B6;
  border-radius: 14px;
  padding: 16px;
  margin: 8px 0;
  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
}

.plan-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.plan-icon {
  font-size: 16px;
}

.plan-title {
  font-size: 14px;
  font-weight: 600;
  color: #1D1D1F;
}

.plan-steps {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #F5F5F7;
  border-radius: 10px;
}

.step-item.is-running {
  background: rgba(46,117,182,0.08);
  border-left: 3px solid #2E75B6;
}

.step-item.is-failed {
  background: rgba(255,59,48,0.06);
  border-left: 3px solid #FF3B30;
}

.step-indicator {
  font-size: 11px;
  color: #999;
  min-width: 80px;
}

.step-content {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.step-status {
  font-size: 14px;
}

.step-agent {
  font-size: 13px;
  font-weight: 500;
  color: #1D1D1F;
}

.step-goal {
  font-size: 13px;
  color: #666;
}

.step-status-text {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}

.plan-footer {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #E5E5EA;
}

.progress-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.progress-label {
  font-size: 12px;
  color: #999;
}

.progress-text {
  font-size: 12px;
  color: #666;
  min-width: 36px;
}
</style>
