<script setup>
import { ref, onMounted, watch, h } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { useRouter } from 'vue-router'
import { NInput, NButton, NSpace, NCard, NModal, NForm, NFormItem, NDatePicker, NAlert, NPopconfirm, NDataTable, NEmpty } from 'naive-ui'
import { updateProfile, changePassword } from '@/api/auth'
import { fetchTokens, createToken, revokeToken } from '@/api/tokens'
import { updateWorkspace } from '@/api/workspaces'
import { fetchDirs } from '@/api/filesystem'

const router = useRouter()
const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

const nickname = ref('')
const email = ref('')
const oldPassword = ref('')
const newPassword = ref('')
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileMsg = ref('')
const passwordMsg = ref('')
const basePath = ref('')
const savingPath = ref(false)
const pathMsg = ref('')
const pathOk = ref(false)
const tokenMsg = ref('')

// --- Directory picker state ---
const dirPickerVisible = ref(false)
const dirPickerPath = ref('')
const dirPickerDirs = ref([])
const dirPickerBreadcrumb = ref([])
const dirPickerLoading = ref(false)
const dirPickerParent = ref(null)
const dirPickerError = ref('')
let loadSeq = 0

// --- PAT Token state ---
const tokens = ref([])
const showCreateToken = ref(false)
const createdToken = ref('')
const newTokenName = ref('')
const newTokenExpiresAt = ref(null)

const tokenColumns = [
  { title: '名称', key: 'name' },
  { title: '创建时间', key: 'createdAt' },
  { title: '过期时间', key: 'expiresAt' },
  {
    title: '操作',
    key: 'actions',
    render(row) {
      return h(NPopconfirm, {
        onPositiveClick: () => handleRevoke(row.id)
      }, {
        trigger: () => h(NButton, { size: 'tiny', type: 'error', text: true }, { default: () => '吊销' }),
        default: () => '确定吊销此 Token？'
      })
    }
  }
]

onMounted(async () => {
  await authStore.refreshProfile()
  nickname.value = authStore.nickname || ''
  loadTokens()
  // Ensure workspace store is loaded before reading basePath
  if (workspaceStore.activeId == null || workspaceStore.workspaces.length === 0) {
    await workspaceStore.loadAndSelect()
  }
  basePath.value = workspaceStore.activeWorkspace?.basePath || ''
})

watch(() => workspaceStore.activeWorkspace, (ws) => {
  basePath.value = ws?.basePath || ''
})

async function loadTokens() {
  try {
    tokens.value = await fetchTokens()
  } catch { tokens.value = [] }
}

async function handleCreateToken() {
  if (!newTokenName.value) return
  tokenMsg.value = ''
  createdToken.value = ''
  try {
    const body = { name: newTokenName.value }
    if (newTokenExpiresAt.value) {
      body.expiresAt = new Date(newTokenExpiresAt.value).toISOString()
    }
    createdToken.value = await createToken(body)
    newTokenName.value = ''
    newTokenExpiresAt.value = null
    await loadTokens()
  } catch (err) {
    tokenMsg.value = err.message || '创建失败'
  }
}

async function handleRevoke(id) {
  tokenMsg.value = ''
  try {
    await revokeToken(id)
    await loadTokens()
  } catch (err) {
    tokenMsg.value = err.message || '吊销失败'
  }
}

async function saveProfile() {
  savingProfile.value = true
  profileMsg.value = ''
  try {
    await updateProfile({ nickname: nickname.value, email: email.value })
    profileMsg.value = '个人信息已更新'
    authStore.nickname = nickname.value
  } catch (err) {
    profileMsg.value = err.message || '更新失败'
  } finally {
    savingProfile.value = false
  }
}

async function savePassword() {
  if (!oldPassword.value || !newPassword.value) {
    passwordMsg.value = '请填写新旧密码'
    return
  }
  savingPassword.value = true
  passwordMsg.value = ''
  try {
    await changePassword(authStore.userId, oldPassword.value, newPassword.value)
    passwordMsg.value = '密码已修改'
    oldPassword.value = ''
    newPassword.value = ''
  } catch (err) {
    passwordMsg.value = err.message || '修改失败'
  } finally {
    savingPassword.value = false
  }
}

async function saveBasePath() {
  if (workspaceStore.activeId == null) {
    pathMsg.value = '工作区未加载，请刷新页面重试'
    return
  }
  savingPath.value = true
  pathMsg.value = ''
  try {
    const data = { basePath: basePath.value || null }
    await updateWorkspace(workspaceStore.activeId, data)
    pathOk.value = true
    pathMsg.value = basePath.value ? '工作目录已更新' : '工作目录已清除'
    await workspaceStore.refresh()
  } catch (err) {
    pathOk.value = false
    pathMsg.value = err.message || '保存失败'
  } finally {
    savingPath.value = false
  }
}

async function openDirPicker() {
  await loadDirs(basePath.value || '')
  dirPickerVisible.value = true
}

async function loadDirs(targetPath) {
  const seq = ++loadSeq
  dirPickerLoading.value = true
  dirPickerError.value = ''
  try {
    const result = await fetchDirs(targetPath || null)
    if (seq !== loadSeq) return
    dirPickerPath.value = result.path
    dirPickerDirs.value = result.dirs || []
    dirPickerParent.value = result.parent || null

    const crumbs = [{ label: '根目录', path: '' }]
    if (result.path) {
      const parts = result.path.replace(/\\/g, '/').split('/').filter(Boolean)
      let built = ''
      for (const part of parts) {
        built = built ? built.replace(/\/$/, '') + '/' + part : (result.path.match(/^[A-Za-z]:/) ? part + '/' : '/' + part)
        crumbs.push({ label: part, path: built })
      }
    }
    dirPickerBreadcrumb.value = crumbs
  } catch (err) {
    if (seq !== loadSeq) return
    dirPickerError.value = '目录加载失败：' + (err.message || '未知错误')
    dirPickerDirs.value = []
  } finally {
    if (seq === loadSeq) {
      dirPickerLoading.value = false
    }
  }
}

function navigateToDir(targetPath) {
  loadDirs(targetPath)
}

function selectDir(targetPath) {
  basePath.value = targetPath
  dirPickerVisible.value = false
}

function closeDirPicker() {
  dirPickerVisible.value = false
  dirPickerDirs.value = []
  dirPickerBreadcrumb.value = []
  dirPickerParent.value = null
  dirPickerError.value = ''
}
</script>

<template>
  <div class="settings-view">
    <div class="page-header">
      <NButton text @click="router.push('/chat')" style="font-size:18px">&larr; 返回</NButton>
      <h2>用户设置</h2>
    </div>

    <NCard title="个人信息" class="settings-card">
      <NSpace vertical :size="12">
        <div>
          <label>用户名</label>
          <NInput :value="authStore.username" disabled />
        </div>
        <div>
          <label>昵称</label>
          <NInput v-model:value="nickname" placeholder="昵称" />
        </div>
        <div>
          <label>角色</label>
          <NInput :value="authStore.role" disabled />
        </div>
        <NButton type="primary" @click="saveProfile" :loading="savingProfile">保存</NButton>
        <span v-if="profileMsg" class="msg">{{ profileMsg }}</span>
      </NSpace>
    </NCard>

    <NCard title="修改密码" class="settings-card">
      <NSpace vertical :size="12">
        <NInput v-model:value="oldPassword" type="password" placeholder="当前密码" />
        <NInput v-model:value="newPassword" type="password" placeholder="新密码" />
        <NButton @click="savePassword" :loading="savingPassword">修改密码</NButton>
        <span v-if="passwordMsg" class="msg">{{ passwordMsg }}</span>
      </NSpace>
    </NCard>

    <NCard title="工作区" class="settings-card">
      <NSpace vertical :size="12">
        <div>
          <label>当前工作区</label>
          <NInput :value="workspaceStore.activeWorkspace?.name || '未选择'" disabled />
        </div>
        <div>
          <label>工作目录（Agent CLI 执行路径）</label>
          <div style="display: flex; gap: 8px;">
            <NInput v-model:value="basePath" placeholder="如 D:\projects\my-app" style="flex: 1;" />
            <NButton @click="openDirPicker" :disabled="workspaceStore.activeId == null">浏览</NButton>
          </div>
          <div v-if="workspaceStore.activeWorkspace?.basePath" style="margin-top: 6px; font-size: 12px; color: #18a058;">
            当前工作目录：{{ workspaceStore.activeWorkspace.basePath }}
          </div>
        </div>
        <NButton type="primary" @click="saveBasePath" :loading="savingPath">保存</NButton>
        <span v-if="pathMsg" :style="{ fontSize: '13px', color: pathOk ? '#34C759' : '#FF3B30' }">{{ pathMsg }}</span>
      </NSpace>
    </NCard>

    <NCard title="关于" class="settings-card">
      <p>AgentHub 多 Agent 协同工作平台 v1.0</p>
    </NCard>

    <NCard title="本地 Agent 接入" size="small" style="margin-top:16px">
      <template #header-extra>
        <NTag type="info" size="small">BETA</NTag>
      </template>
      <NSpace vertical>
        <NText>
          使用本地 CLI Agent（Claude Code、OpenCode 等）接入平台：
        </NText>
        <NCard size="small" embedded>
          <NCode code="claude --bridge wss://your-host/api/v1/agent-bridge/ws?token=mc_YOUR_PAT_TOKEN \
       --name &quot;我的Claude&quot; \
       --work-dir /home/dev/project" language="bash" />
        </NCard>
        <NText depth="3">
          1. 先从上方「个人访问令牌」生成一个 PAT<br/>
          2. 在本地终端执行上述命令<br/>
          3. 连接成功后，Agent 将出现在 Agent 管理列表中
        </NText>
      </NSpace>
    </NCard>

    <NCard title="Personal Access Tokens" class="settings-card">
      <NSpace vertical :size="12">
        <NButton @click="showCreateToken = true; createdToken = ''; newTokenName = ''; newTokenExpiresAt = null; tokenMsg = ''" type="primary" ghost>
          + 新建 Token
        </NButton>
        <NDataTable
          v-if="tokens.length"
          :columns="tokenColumns"
          :data="tokens"
          :bordered="false"
          size="small"
        />
        <NEmpty v-else description="暂无 Token" />
      </NSpace>
    </NCard>

    <NModal v-model:show="showCreateToken" title="新建 Access Token">
      <NForm>
        <NFormItem label="名称">
          <NInput v-model:value="newTokenName" placeholder="如 CLI Access" />
        </NFormItem>
        <NFormItem label="过期时间（可选）">
          <NDatePicker v-model:value="newTokenExpiresAt" type="datetime" />
        </NFormItem>
      </NForm>
      <NAlert
        v-if="createdToken"
        type="success"
        :title="'Token 已创建，请保存：' + createdToken"
      />
      <template #action>
        <span v-if="tokenMsg" style="color: #FF3B30; font-size: 13px; margin-right: 12px;">{{ tokenMsg }}</span>
        <NButton v-if="!createdToken" @click="handleCreateToken">创建</NButton>
        <NButton v-else @click="showCreateToken = false">关闭</NButton>
      </template>
    </NModal>

    <NModal v-model:show="dirPickerVisible" title="选择工作目录" style="width: 520px;">
      <div style="padding: 12px 0;">
        <div style="display: flex; align-items: center; gap: 2px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px;">
          <span style="color: #333; margin-right: 4px;">路径：</span>
          <template v-for="(crumb, idx) in dirPickerBreadcrumb" :key="idx">
            <span v-if="idx > 0" style="color: #666;">&rsaquo;</span>
            <NButton
              text
              size="tiny"
              @click="navigateToDir(crumb.path)"
              :style="{ color: idx === dirPickerBreadcrumb.length - 1 ? '#333' : '#1890ff', fontWeight: idx === dirPickerBreadcrumb.length - 1 ? '500' : 'normal' }"
            >
              {{ crumb.label }}
            </NButton>
          </template>
        </div>

        <div v-if="dirPickerError" style="margin-bottom: 8px; font-size: 13px; color: #FF3B30;">{{ dirPickerError }}</div>

        <div style="max-height: 300px; overflow-y: auto; border: 1px solid #e8e8e8; border-radius: 6px;">
          <div v-if="dirPickerLoading" style="padding: 40px; text-align: center; color: #666;">加载中...</div>
          <div v-else-if="dirPickerDirs.length === 0 && !dirPickerError && !dirPickerParent" style="padding: 40px; text-align: center; color: #666;">此目录为空或无法访问</div>
          <template v-else>
            <div v-if="dirPickerParent"
              style="padding: 8px 12px; display: flex; align-items: center; gap: 8px; cursor: pointer; border-bottom: 1px solid #f0f0f0; font-size: 14px;"
              @click="navigateToDir(dirPickerParent)">
              <span style="font-size: 16px;">&#x1F4C2;</span>
              <span style="flex: 1; color: #1890ff;">返回上级</span>
            </div>
            <div
              v-for="entry in dirPickerDirs"
              :key="entry.path"
              style="padding: 8px 12px; display: flex; align-items: center; gap: 8px; cursor: pointer; border-bottom: 1px solid #f0f0f0; font-size: 14px;"
              :style="{ background: entry.path === basePath ? '#e6f7ff' : 'transparent' }"
              @click="navigateToDir(entry.path)"
            >
              <span style="font-size: 16px;">&#x1F4C1;</span>
              <span style="flex: 1;">{{ entry.name }}</span>
              <NButton size="tiny" @click.stop="selectDir(entry.path)">选择</NButton>
            </div>
          </template>
        </div>
      </div>

      <template #footer>
        <NSpace justify="end">
          <NButton @click="closeDirPicker">取消</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.settings-view { padding: 24px; max-width: 640px; margin: 0 auto; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
.page-header h2 { font-size: 22px; font-weight: 600; }
.settings-card { margin-bottom: 16px; border-radius: 14px; }
label { font-size: 13px; color: #666; display: block; margin-bottom: 4px; }
.msg { font-size: 13px; color: #34C759; }
</style>
