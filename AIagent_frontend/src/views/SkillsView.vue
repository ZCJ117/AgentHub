<script setup>
import { ref, computed } from 'vue'
import { NTabs, NTabPane, NButton, NTag, NModal, NSpin, NIcon, NCard } from 'naive-ui'
import { CopyOutline } from '@vicons/ionicons5'
import { scanGlobalSkills } from '@/api/skills'

const activeTab = ref('claude_code')
const loading = ref(false)
const claudeSkills = ref([])
const opencodeSkills = ref([])
const scanned = ref(false)

const installModal = ref(false)
const installTarget = ref(null)

const scanPaths = {
  claude_code: '~/.claude/skills/',
  opencode: '~/.config/opencode/skills/'
}

const skills = computed(() => {
  return activeTab.value === 'opencode' ? opencodeSkills.value : claudeSkills.value
})

async function handleScan() {
  loading.value = true
  try {
    const [claudeRes, openRes] = await Promise.all([
      scanGlobalSkills('claude_code'),
      scanGlobalSkills('opencode')
    ])
    claudeSkills.value = Array.isArray(claudeRes) ? claudeRes : (claudeRes?.records || [])
    opencodeSkills.value = Array.isArray(openRes) ? openRes : (openRes?.records || [])
    scanned.value = true
  } finally {
    loading.value = false
  }
}

function openInstall(skill) {
  installTarget.value = skill
  installModal.value = true
}

function copyCommand() {
  if (installTarget.value) {
    navigator.clipboard.writeText(installTarget.value.installCommand)
  }
}
</script>

<template>
  <div class="skills-page">
    <NCard>
      <div class="skills-header">
        <h2>🔧 全局技能管理</h2>
        <p class="subtitle">管理 Claude Code 和 OpenCode 的全局 Skills</p>
      </div>

      <NTabs v-model:value="activeTab" type="segment" animated>
        <NTabPane name="claude_code" tab="Claude Code" />
        <NTabPane name="opencode" tab="OpenCode" />
      </NTabs>

      <div class="scan-area">
        <NButton type="primary" :loading="loading" @click="handleScan" size="large">
          🔍 扫描全局 Skills
        </NButton>
        <p class="scan-path">扫描目录: {{ scanPaths.claude_code }}</p>
        <p class="scan-path">　　　　　{{ scanPaths.opencode }}</p>
      </div>

      <NSpin :show="loading">
        <div v-if="scanned && skills.length" class="skill-list">
          <div class="skill-list-header">
            <span class="col-name">Skill 名称</span>
            <span class="col-desc">核心功能</span>
            <span class="col-count">安装量</span>
            <span class="col-status">状态</span>
            <span class="col-action">操作</span>
          </div>
          <div
            v-for="skill in skills"
            :key="skill.name"
            class="skill-row"
            :class="{ 'not-installed': !skill.installed }"
          >
            <span class="col-name"><strong>{{ skill.name }}</strong></span>
            <span class="col-desc">{{ skill.description }}</span>
            <span class="col-count">{{ skill.installCount }}</span>
            <span class="col-status">
              <NTag :type="skill.installed ? 'success' : 'error'" size="small">
                {{ skill.installed ? '已安装' : '未安装' }}
              </NTag>
            </span>
            <span class="col-action">
              <NButton
                v-if="!skill.installed"
                size="small"
                @click="openInstall(skill)"
              >
                安装
              </NButton>
            </span>
          </div>
        </div>
        <div v-else-if="scanned && !skills.length" class="empty-state">
          <p>暂无可扫描的技能数据</p>
        </div>
      </NSpin>
    </NCard>

    <!-- 安装命令 Modal -->
    <NModal v-model:show="installModal" title="安装 Skill">
      <div v-if="installTarget" class="install-modal">
        <p>请在终端中执行以下命令：</p>
        <div class="command-block">
          <code>{{ installTarget.installCommand }}</code>
        </div>
        <NButton @click="copyCommand" size="small">
          <template #icon><NIcon :component="CopyOutline" /></template>
          复制命令
        </NButton>
      </div>
    </NModal>
  </div>
</template>

<style scoped>
.skills-page {
  max-width: 960px;
  margin: 24px auto;
  padding: 0 16px;
}
.skills-header {
  text-align: center;
  margin-bottom: 16px;
}
.skills-header h2 {
  margin: 0;
}
.subtitle {
  color: #888;
  font-size: 13px;
  margin: 4px 0 0;
}
.scan-area {
  text-align: center;
  margin: 20px 0;
}
.scan-path {
  color: #aaa;
  font-size: 12px;
  margin-top: 6px;
}
.skill-list-header, .skill-row {
  display: flex;
  padding: 10px 12px;
  font-size: 13px;
  align-items: center;
}
.skill-list-header {
  font-weight: 600;
  color: #666;
  border-bottom: 2px solid #eee;
}
.skill-row {
  border-bottom: 1px solid #f0f0f0;
}
.skill-row.not-installed {
  background: #fffbf0;
}
.col-name { flex: 2; }
.col-desc { flex: 4; }
.col-count { flex: 1; text-align: center; }
.col-status { flex: 1.5; text-align: center; }
.col-action { flex: 1; text-align: center; }
.empty-state {
  text-align: center;
  padding: 40px;
  color: #999;
}
.install-modal {
  padding: 12px;
}
.install-modal p {
  margin: 0 0 8px;
}
.command-block {
  background: #1e1e1e;
  color: #4ec9b0;
  padding: 12px 16px;
  border-radius: 6px;
  font-family: monospace;
  font-size: 14px;
  margin-bottom: 12px;
}
</style>
