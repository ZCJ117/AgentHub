# PAT Token + 工作区切换 + Agent 头像上传 — 前端设计

日期：2026-05-23 | 优先级：中 | 状态：已确认

---

## 1. 新增 `src/api/tokens.js`

PAT Token CRUD API 封装：

```js
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
```

不包含 scopes 字段（后端可选，UI 保持简洁）。

---

## 2. SettingsView.vue — PAT Token 管理区域

在 "关于" 卡片下方新增 `Access Tokens` 卡片。

**卡片内容：**
- "新建 Token" 按钮（`type="primary" ghost`）→ 弹出创建对话框
- `<n-data-table>` 列：名称、创建时间、过期时间、操作（吊销）
- 吊销使用 `<n-popconfirm>` 二次确认
- 空列表显示 `<n-empty description="暂无 Token" />`

**创建对话框 (`<n-modal`):**
- 表单：名称（Input，必填）、过期时间（DatePicker datetime，可选）
- 创建成功后 `<n-alert type="success">` 显示明文 token（仅此一次可见）
- 关闭弹窗后清除已显示的 token

**数据加载：**
- `onMounted` 调用 `fetchTokens()` 加载列表
- 创建/吊销后刷新列表

**组件引入：** 新增 `NModal`, `NForm`, `NFormItem`, `NDatePicker`, `NAlert`, `NPopconfirm`, `NDataTable`, `NEmpty`。

---

## 3. `src/stores/workspace.js` — 新增 `selectWorkspace`

运行时切换工作区，集中触发各 store 刷新：

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

  // orchestrator 依赖 conversationId，无法独立加载；清除状态，页面按需刷新
  useOrchestratorStore().reset()
}
```

- 使用动态 `import()` 避免循环依赖
- `orchestrator.reset()` 而非 `loadTasks()`（后者需要 conversationId，切换工作区后让各页面 `onMounted` 按需加载）

---

## 4. TopBar.vue — 工作区切换器

**替换静态 badge** 为 `<n-dropdown>` 下拉选择器。

**图标：** `@vicons/ionicons5` — `BusinessOutline`、`ChevronDownOutline`

**脚本新增：**
```js
import { BusinessOutline, ChevronDownOutline } from '@vicons/ionicons5'
import { NDropdown, NIcon } from 'naive-ui'

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

**模板：**
```html
<n-dropdown trigger="click" :options="workspaceOptions" @select="handleSwitchWorkspace">
  <n-button text class="workspace-switcher">
    <template #icon><n-icon :component="BusinessOutline" /></template>
    {{ workspaceStore.activeWorkspace?.name || '选择工作区' }}
    <n-icon :component="ChevronDownOutline" />
  </n-button>
</n-dropdown>
```

**样式：** `.workspace-switcher` 半透明背景（`#F5F5F7`）、`border-radius: 8px`、`padding: 4px 10px`，hover 加深。

---

## 5. AgentDetailView.vue — 头像上传

**替换静态头像**（第 92 行），添加上传交互。

**图标：** `@vicons/ionicons5` — `CameraOutline`

**模板：**
```html
<div class="avatar-upload" @click="triggerUpload">
  <n-avatar :size="80" round :src="agentDetail.avatarUrl" :fallback="(agentDetail.name || 'AI')[0]" />
  <div class="avatar-overlay">
    <n-icon :component="CameraOutline" />
    <span>更换</span>
  </div>
  <input ref="fileInput" type="file" accept="image/*" style="display:none" @change="handleAvatarUpload" />
</div>
```

**`agentDetail` computed：**
```js
import { computed } from 'vue'
const agentDetail = computed(() => store.detailCache.get(Number(agentId.value)) || {})
```

**上传逻辑：**
```js
import { getToken } from '@/utils/token'

const fileInput = ref(null)
function triggerUpload() { fileInput.value?.click() }

async function handleAvatarUpload(e) {
  const file = e.target.files[0]
  if (!file) return
  const formData = new FormData()
  formData.append('file', file)

  // 使用原生 fetch — axios 默认 JSON 拦截器会破坏 multipart body
  const token = getToken()
  await fetch(`/api/v1/agents/${agentId.value}/avatar`, {
    method: 'PUT',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
  })
  // 清除缓存并重新加载
  store.detailCache.delete(Number(agentId.value))
  await store.loadDetail(Number(agentId.value))
}
```

**使用 `getToken()`**（来自 `@/utils/token`），与 `api/client.js` 保持一致，而非 `localStorage` 直接读取。

**样式：** `.avatar-upload` 添加 `position: relative; cursor: pointer`。`.avatar-overlay` 半透明黑色遮罩，hover 时 opacity 1，中央显示相机图标 + "更换" 文字。

---

## 变更文件清单

| 文件 | 操作 |
|------|------|
| `src/api/tokens.js` | 新建 |
| `src/views/SettingsView.vue` | 修改：新增 PAT Token 卡片 |
| `src/stores/workspace.js` | 修改：新增 `selectWorkspace` |
| `src/components/layout/TopBar.vue` | 修改：badge → 下拉选择器 |
| `src/views/AgentDetailView.vue` | 修改：头像上传 |

无后端改动（API 均已就绪）。
