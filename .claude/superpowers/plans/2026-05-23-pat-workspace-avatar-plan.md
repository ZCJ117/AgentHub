# PAT Token + 工作区切换 + Agent 头像上传 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 PAT 管理、工作区切换器、Agent 头像上传三个功能的前端 UI，后端 API 均已就绪。

**Architecture:** 集中式方案 — workspace store 的 `selectWorkspace()` 统一触发 agent/conversation/artifact 列表重载，orchestrator 执行 reset。头像上传使用原生 fetch 绕过 axios JSON 拦截器。PAT Token 管理在 SettingsView 中以新卡片形式呈现。

**Tech Stack:** Vue 3 + Composition API + Pinia + Naive UI 2.x + @vicons/ionicons5 + Axios

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/api/tokens.js` | 新建 | PAT Token CRUD API 封装 |
| `src/stores/workspace.js` | 修改 | 新增 `selectWorkspace()` 集中式切换 |
| `src/views/SettingsView.vue` | 修改 | 新增 PAT Token 管理卡片 |
| `src/components/layout/TopBar.vue` | 修改 | badge → 下拉工作区选择器 |
| `src/views/AgentDetailView.vue` | 修改 | 头像 hover → 上传交互 |

---

### Task 1: 新建 PAT Token API 模块

**Files:**
- Create: `AIagent_frontend/src/api/tokens.js`

- [ ] **Step 1: 创建 tokens API 模块**

```bash
cat > AIagent_frontend/src/api/tokens.js << 'EOF'
import apiClient from './client'

export function fetchTokens() {
  return apiClient.get('/api/v1/auth/tokens')
}

export function createToken(data) {
  return apiClient.post('/api/v1/auth/tokens', data)
}

export function revokeToken(id) {
  return apiClient.delete(`/api/v1/auth/tokens/${id}`)
}
EOF
```

- [ ] **Step 2: 验证文件语法**

```bash
cd AIagent_frontend && npx eslint src/api/tokens.js --no-error-on-unmatched-pattern 2>&1 || true
```

- [ ] **Step 3: 提交**

```bash
cd D:/code/Loom && git add AIagent_frontend/src/api/tokens.js && git commit -m "feat: add PAT token API module (fetch, create, revoke)"
```

---

### Task 2: Workspace Store — 新增 selectWorkspace

**Files:**
- Modify: `AIagent_frontend/src/stores/workspace.js`

- [ ] **Step 1: 添加 selectWorkspace 方法**

在 `loadAndSelect` 函数之后、return 语句之前（第 31 行后），插入：

```js
  async function selectWorkspace(id) {
    activeId.value = id
    localStorage.setItem('ai_agent_workspace_id', String(id))

    const { useConversationStore } = await import('@/stores/conversation')
    const { useAgentStore } = await import('@/stores/agent')
    const { useArtifactStore } = await import('@/stores/artifact')
    const { useOrchestratorStore } = await import('@/stores/orchestrator')

    useConversationStore().loadList()
    useAgentStore().loadAgents({ enabled: true })
    useArtifactStore().loadList()
    useOrchestratorStore().reset()
  }
```

- [ ] **Step 2: 将 selectWorkspace 加入 return 对象**

将 return 语句从：
```js
return { workspaces, activeId, activeWorkspace, loadAndSelect }
```
改为：
```js
return { workspaces, activeId, activeWorkspace, loadAndSelect, selectWorkspace }
```

- [ ] **Step 3: 提交**

```bash
cd D:/code/Loom && git add AIagent_frontend/src/stores/workspace.js && git commit -m "feat: add selectWorkspace to workspace store with centralized store reload"
```

---

### Task 3: SettingsView — PAT Token 管理区域

**Files:**
- Modify: `AIagent_frontend/src/views/SettingsView.vue`

- [ ] **Step 1: 更新 script 部分 — 导入和状态**

将现有 `<script setup>` 的内容替换为（在原基础上增加）：

```js
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import {
  NInput, NButton, NSpace, NCard, NModal, NForm, NFormItem,
  NDatePicker, NAlert, NPopconfirm, NDataTable, NEmpty
} from 'naive-ui'
import { updateProfile, changePassword } from '@/api/auth'
import { fetchTokens, createToken, revokeToken } from '@/api/tokens'

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
})

async function loadTokens() {
  try {
    tokens.value = await fetchTokens()
  } catch { tokens.value = [] }
}

async function handleCreateToken() {
  if (!newTokenName.value) return
  try {
    const body = { name: newTokenName.value }
    if (newTokenExpiresAt.value) {
      body.expiresAt = new Date(newTokenExpiresAt.value).toISOString()
    }
    createdToken.value = await createToken(body)
    showCreateToken.value = false
    newTokenName.value = ''
    newTokenExpiresAt.value = null
    await loadTokens()
  } catch (err) {
    console.warn('Create token failed:', err)
  }
}

async function handleRevoke(id) {
  try {
    await revokeToken(id)
    await loadTokens()
  } catch (err) {
    console.warn('Revoke token failed:', err)
  }
}

// --- existing functions ---
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
```

注意：由于 token 表格的 `render` 函数使用了 `h`，需要在 import 中加入 `h`：
```js
import { ref, onMounted, h } from 'vue'
```

- [ ] **Step 2: 更新模板 — 在 About 卡片后追加 PAT Token 卡片**

在 `</NCard>`（"关于" 卡片结束）之后、`</div>`（`.settings-view` 结束）之前插入：

```html
    <NCard title="Personal Access Tokens" class="settings-card">
      <NSpace vertical :size="12">
        <NButton @click="showCreateToken = true" type="primary" ghost>
          + 新建 Token
        </NButton>
        <NDataTable
          v-if="tokens.length > 0"
          :columns="tokenColumns"
          :data="tokens"
          :bordered="false"
          size="small"
        />
        <NEmpty v-if="tokens.length === 0" description="暂无 Token" />
      </NSpace>
    </NCard>

    <!-- 创建 Token 弹窗 -->
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
        <NButton @click="handleCreateToken">创建</NButton>
      </template>
    </NModal>
```

- [ ] **Step 3: 验证编译**

```bash
cd AIagent_frontend && npm run build 2>&1 | tail -20
```

预计：BUILD SUCCESS（无类型错误）。

- [ ] **Step 4: 提交**

```bash
cd D:/code/Loom && git add AIagent_frontend/src/views/SettingsView.vue && git commit -m "feat: add PAT token management UI in SettingsView"
```

---

### Task 4: TopBar — 工作区切换下拉选择器

**Files:**
- Modify: `AIagent_frontend/src/components/layout/TopBar.vue`

- [ ] **Step 1: 更新 script — 添加导入和逻辑**

在现有 `<script setup>` 中：

**新增 imports（第 2 行后）：**
```js
import { computed } from 'vue'
import { BusinessOutline, ChevronDownOutline } from '@vicons/ionicons5'
import { NDropdown, NIcon } from 'naive-ui'
```

注：`computed` 当前未在 TopBar 中使用，需要添加。`NIcon` 可能也未引入，需要与 NDropdown 一起从 naive-ui 引入。

**新增 computed 和 handler（在 `handleLogout` 函数之后）：**
```js
const workspaceOptions = computed(() =>
  workspaceStore.workspaces.map(w => ({
    label: w.name,
    value: w.id
  }))
)

function handleSwitchWorkspace(id) {
  workspaceStore.selectWorkspace(id)
}
```

- [ ] **Step 2: 更新模板 — 替换静态 badge**

将第 58-60 行的：
```html
      <span v-if="workspaceStore.activeWorkspace" class="ws-badge">
        {{ workspaceStore.activeWorkspace.name || '工作区' }}
      </span>
```
替换为：
```html
      <n-dropdown
        trigger="click"
        :options="workspaceOptions"
        @select="handleSwitchWorkspace"
      >
        <n-button text class="workspace-switcher">
          <template #icon>
            <n-icon :component="BusinessOutline" />
          </template>
          {{ workspaceStore.activeWorkspace?.name || '选择工作区' }}
          <n-icon :component="ChevronDownOutline" />
        </n-button>
      </n-dropdown>
```

- [ ] **Step 3: 更新样式 — 添加 .workspace-switcher**

在 `<style scoped>` 中，替换 `.ws-badge` 样式块为：

```css
.workspace-switcher {
  font-size: 13px;
  color: #666;
  background: #F5F5F7;
  padding: 4px 10px;
  border-radius: 8px;
  white-space: nowrap;
  gap: 6px;
}
.workspace-switcher:hover {
  background: #E8E8ED;
}
```

- [ ] **Step 4: 验证编译**

```bash
cd AIagent_frontend && npm run build 2>&1 | tail -20
```

- [ ] **Step 5: 提交**

```bash
cd D:/code/Loom && git add AIagent_frontend/src/components/layout/TopBar.vue && git commit -m "feat: replace workspace badge with dropdown switcher in TopBar"
```

---

### Task 5: AgentDetailView — 头像上传

**Files:**
- Modify: `AIagent_frontend/src/views/AgentDetailView.vue`

- [ ] **Step 1: 更新 script — 添加导入和上传逻辑**

**新增 imports（在第 3 行 import 语句之后）：**
```js
import { ref, onMounted, computed } from 'vue'
import { CameraOutline } from '@vicons/ionicons5'
import { getToken } from '@/utils/token'
```

注：`computed` 需要在现有的 `import { ref, onMounted, computed }` 中已存在 — 检查第 2 行，如果 `computed` 已存在则不需要重复添加，只需将 `computed` 加入已有的解构中。

**新增 computed 和上传逻辑（在 `remove` 函数之后、`</script>` 之前）：**
```js
const agentDetail = computed(() =>
  store.detailCache.get(Number(agentId.value)) || {}
)

const fileInput = ref(null)

function triggerUpload() {
  fileInput.value?.click()
}

async function handleAvatarUpload(e) {
  const file = e.target.files[0]
  if (!file) return
  const formData = new FormData()
  formData.append('file', file)

  const token = getToken()
  await fetch(`/api/v1/agents/${agentId.value}/avatar`, {
    method: 'PUT',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
  })
  store.detailCache.delete(Number(agentId.value))
  await store.loadDetail(Number(agentId.value))
}
```

- [ ] **Step 2: 更新模板 — 替换静态头像**

将第 92-93 行的：
```html
        <NAvatar :size="80" round :src="null" class="avatar">
          {{ (name || 'AI')[0] }}
        </NAvatar>
```
替换为：
```html
        <div class="avatar-upload" @click="triggerUpload">
          <NAvatar :size="80" round :src="agentDetail.avatarUrl" :fallback="(agentDetail.name || 'AI')[0]" />
          <div class="avatar-overlay">
            <NIcon :component="CameraOutline" />
            <span>更换</span>
          </div>
          <input
            ref="fileInput"
            type="file"
            accept="image/*"
            style="display:none"
            @change="handleAvatarUpload"
          />
        </div>
```

注意：`NIcon` 需要确认已在 naive-ui 的 import 中。检查第 5 行，如果没有则添加。

- [ ] **Step 3: 更新样式 — 添加 avatar-upload 样式**

在 `<style scoped>` 中的 `.detail-sidebar` 样式块之前或之后添加：

```css
.avatar-upload {
  position: relative;
  cursor: pointer;
  border-radius: 50%;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.2s;
  font-size: 12px;
  gap: 4px;
}
.avatar-upload:hover .avatar-overlay {
  opacity: 1;
}
```

- [ ] **Step 4: 验证编译**

```bash
cd AIagent_frontend && npm run build 2>&1 | tail -20
```

- [ ] **Step 5: 提交**

```bash
cd D:/code/Loom && git add AIagent_frontend/src/views/AgentDetailView.vue && git commit -m "feat: add agent avatar upload on AgentDetailView"
```

---

## 验证清单

全部任务完成后，逐项验证：

1. **PAT Token 管理**
   - 打开 Settings 页面 → 看到 "Personal Access Tokens" 卡片
   - 点击 "新建 Token" → 弹出模态框 → 输入名称 → 创建 → 显示明文 token
   - 表格中出现新 token → 点击 "吊销" → 二次确认 → token 消失

2. **工作区切换**
   - TopBar 显示当前工作区名称（带 Building 图标 + 下拉箭头）
   - 点击 → 下拉列表展示所有工作区
   - 选择另一个工作区 → conversations / agents 列表刷新
   - 刷新页面 → 工作区保持选中

3. **头像上传**
   - 打开 Agent 详情页 → 头像正常显示（有 avatarUrl 则显示图片，否则显示首字母）
   - 鼠标 hover 头像 → 出现黑色遮罩 + "更换" 提示
   - 点击 → 文件选择器打开 → 选择图片 → 上传 → 头像刷新
