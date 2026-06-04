import apiClient from './client'

/**
 * 扫描全局技能
 * @param {'claude_code' | 'opencode'} type
 */
export function scanGlobalSkills(type = 'claude_code') {
  return apiClient.post('/api/v1/skills/global/scan', null, {
    params: { type }
  })
}
