import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import apiClient from '@/api/client'

export const useOrchestratorStore = defineStore('orchestrator', () => {
  const currentTask = ref(null)
  const assignments = ref([])
  const taskHistory = ref([])

  const progressPercent = computed(() => {
    if (!currentTask.value || currentTask.value.totalAssignments === 0) return 0
    return Math.round(
      (currentTask.value.completedAssignments / currentTask.value.totalAssignments) * 100
    )
  })

  const activeAssignments = computed(() =>
    assignments.value.filter(a => a.status === 'running')
  )

  const failedAssignments = computed(() =>
    assignments.value.filter(a => a.status === 'failed')
  )

  async function loadTasks(conversationId) {
    try {
      const data = await apiClient.get('/api/v1/orchestrator/tasks', {
        params: { conversationId }
      })
      taskHistory.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load orchestrator tasks:', err)
    }
  }

  async function loadTaskDetail(taskId) {
    try {
      currentTask.value = await apiClient.get(`/api/v1/orchestrator/tasks/${taskId}`)
    } catch (err) {
      console.warn('Failed to load task detail:', err)
    }
  }

  async function loadAssignments(taskId) {
    try {
      const data = await apiClient.get(`/api/v1/orchestrator/tasks/${taskId}/assignments`)
      assignments.value = Array.isArray(data) ? data : (data?.records || [])
    } catch (err) {
      console.warn('Failed to load assignments:', err)
    }
  }

  async function retryAssignments(taskId, assignmentIds) {
    try {
      await apiClient.post(`/api/v1/orchestrator/tasks/${taskId}/retry`, {
        assignmentIds
      })
    } catch (err) {
      console.warn('Failed to retry:', err)
    }
  }

  async function cancelTask(taskId) {
    try {
      await apiClient.post(`/api/v1/orchestrator/tasks/${taskId}/cancel`)
    } catch (err) {
      console.warn('Failed to cancel task:', err)
    }
  }

  // Called from SSE events in chat store
  function handleOrchestratorPlan(data) {
    currentTask.value = {
      id: data.taskId,
      title: data.title,
      planJson: { steps: data.steps || [] },
      status: 'running',
      totalAssignments: data.totalAssignments || (data.steps || []).length,
      completedAssignments: 0,
      failedAssignments: 0
    }
  }

  function handleDelegationProgress(data) {
    // Update assignment status in-place
    const idx = assignments.value.findIndex(a =>
      a.agentName === data.agentName || a.agentId === data.agentId
    )
    if (idx !== -1) {
      assignments.value[idx] = {
        ...assignments.value[idx],
        status: data.status,
        resultSummary: data.summary || assignments.value[idx]?.resultSummary
      }
    } else {
      assignments.value.push({
        agentName: data.agentName,
        agentId: data.agentId,
        status: data.status,
        resultSummary: data.summary || '',
        stepOrder: assignments.value.length + 1
      })
    }

    // Update task counters
    if (currentTask.value) {
      const completed = assignments.value.filter(a => a.status === 'completed').length
      const failed = assignments.value.filter(a => a.status === 'failed').length
      currentTask.value.completedAssignments = completed
      currentTask.value.failedAssignments = failed
      if (completed + failed >= currentTask.value.totalAssignments) {
        currentTask.value.status = failed > 0 ? 'failed' : 'completed'
      }
    }
  }

  function reset() {
    currentTask.value = null
    assignments.value = []
  }

  return {
    currentTask, assignments, taskHistory,
    progressPercent, activeAssignments, failedAssignments,
    loadTasks, loadTaskDetail, loadAssignments,
    retryAssignments, cancelTask,
    handleOrchestratorPlan, handleDelegationProgress,
    reset
  }
})
