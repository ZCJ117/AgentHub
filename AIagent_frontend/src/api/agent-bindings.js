import apiClient from './client'

// ── Skill catalog ──
export function fetchEnabledSkills() {
  return apiClient.get('/api/v1/skills/enabled')
}

// ── Tool catalog ──
export function fetchAvailableTools() {
  return apiClient.get('/api/v1/tools/available')
}

// ── Agent skill bindings ──
export function fetchAgentSkills(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/skills`)
}

export function updateAgentSkills(agentId, skillIds) {
  return apiClient.put(`/api/v1/agents/${agentId}/skills`, skillIds)
}

// ── Agent tool bindings ──
export function fetchAgentTools(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/tools`)
}

export function updateAgentTools(agentId, toolNames) {
  return apiClient.put(`/api/v1/agents/${agentId}/tools`, toolNames)
}

// ── Provider preferences ──
export function fetchProviderPreferences(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/provider-preferences`)
}

export function updateProviderPreferences(agentId, providerIds) {
  return apiClient.put(`/api/v1/agents/${agentId}/provider-preferences`, providerIds)
}

// ── Stats ──
export function fetchAgentStats(agentId) {
  return apiClient.get(`/api/v1/agents/${agentId}/stats`)
}
