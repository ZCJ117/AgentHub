# Toolbar 图标化 & 新建入口整合 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 导航 pill 改为纯图标 + tooltip，新建入口从侧边栏两个按钮整合为 TopBar 圆形加号下拉菜单

**Architecture:** 新建 `useAgentSelector` composable 存储共享状态（showAgentSelector + selectorMode + open 方法），ConversationSidebar 和 TopBar 共用，替代 provide/inject（兄弟组件无法直接 provide/inject）

**Tech Stack:** Vue 3 Composition API + Naive UI (NTooltip, NIcon, NDropdown) + @vicons/ionicons5

**说明:** 设计文档中 provide/inject 方案因兄弟组件树方向问题不可行，改用 composable 共享状态。功能行为不变。

---

### Task 1: 创建 shared composable `useAgentSelector`

**Files:**
- Create: `AIagent_frontend/src/composables/useAgentSelector.js`

- [ ] **Step 1: 创建 composable 文件**

```js
import { ref } from 'vue'

const showAgentSelector = ref(false)
const selectorMode = ref('direct')

export function useAgentSelector() {
  function openAgentSelector(mode) {
    selectorMode.value = mode
    showAgentSelector.value = true
  }

  return {
    showAgentSelector,
    selectorMode,
    openAgentSelector
  }
}
```

模块级 `ref` 确保所有调用者共享同一份状态。

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/composables/useAgentSelector.js
git commit -m "feat: add useAgentSelector composable for shared agent selector state"
```

---

### Task 2: 改造 ConversationSidebar — 移除新建按钮，改用 composable

**Files:**
- Modify: `AIagent_frontend/src/components/chat/ConversationSidebar.vue:1-125`

- [ ] **Step 1: 引入 composable，替换局部 ref**

将第 15-16 行的局部 ref 替换为 composable：

```js
// 删除这两行:
// const showAgentSelector = ref(false)
// const selectorMode = ref('direct')

// 改为:
import { useAgentSelector } from '@/composables/useAgentSelector'
const { showAgentSelector, selectorMode, openAgentSelector } = useAgentSelector()
```

import 语句加在第 8 行 `import AgentSelector` 之前。

- [ ] **Step 2: 移除新建按钮**

删除模板第 118-125 行的 NSpace 包裹块：

```html
<!-- 删除这整个 NSpace -->
<NSpace :size="8" style="width: 100%">
  <NButton type="primary" block @click="selectorMode = 'direct'; showAgentSelector = true">
    + 新建对话
  </NButton>
  <NButton block @click="selectorMode = 'group'; showAgentSelector = true">
    + 新建群聊
  </NButton>
</NSpace>
```

模板中 sidebar-header 的 NSpace vertical 内第一个子元素不再是按钮 NSpace，而直接是 type-filter。

- [ ] **Step 3: 清理未使用的 import**

```js
// NButton 仍被 type-filter 和 more-btn 使用，保留
// NButton 保留在 import 中
```

无需删除 import。

- [ ] **Step 4: 验证 AgentSelector 绑定不变**

确保第 219-225 行的 AgentSelector 绑定仍然正确：
```html
<AgentSelector
  :show="showAgentSelector"
  :agents="agentStore.agents"
  :mode="selectorMode"
  @close="showAgentSelector = false"
  @create="handleCreateConversation"
/>
```

此处无需改动，因为 composable 返回的 `showAgentSelector` 和 `selectorMode` 与原来同名同类型。

- [ ] **Step 5: Commit**

```bash
git add AIagent_frontend/src/components/chat/ConversationSidebar.vue
git commit -m "refactor: remove create buttons from sidebar, use shared composable state"
```

---

### Task 3: 改造 TopBar — 图标化 pill + 新增新建菜单

**Files:**
- Modify: `AIagent_frontend/src/components/layout/TopBar.vue`

- [ ] **Step 1: 更新 imports**

```js
// 当前第 8-9 行:
import { BusinessOutline, ChevronDownOutline } from '@vicons/ionicons5'
import { NButton, NDropdown, NIcon } from 'naive-ui'

// 改为:
import {
  BusinessOutline, ChevronDownOutline,
  HardwareChipOutline, CubeOutline, SettingsOutline,
  AddOutline, ChatbubbleOutline, PeopleOutline
} from '@vicons/ionicons5'
import { NButton, NDropdown, NIcon, NTooltip } from 'naive-ui'
import { h } from 'vue'
import { useAgentSelector } from '@/composables/useAgentSelector'
```

- [ ] **Step 2: 扩展 navItems**

```js
// 当前第 16-20 行:
const navItems = [
  { key: 'agents',  label: 'Agent',  path: '/agents' },
  { key: 'artifacts', label: '产物',  path: '/artifacts' },
  { key: 'settings',  label: '设置',  path: '/settings' }
]

// 改为:
const navItems = [
  { key: 'agents',    icon: HardwareChipOutline, tooltip: 'Agent', path: '/agents' },
  { key: 'artifacts', icon: CubeOutline,         tooltip: '产物',  path: '/artifacts' },
  { key: 'settings',  icon: SettingsOutline,     tooltip: '设置',  path: '/settings' }
]
```

- [ ] **Step 3: 添加 createOptions 和 handleCreate**

在 `navigate` 函数后（第 32 行后）添加：

```js
const { openAgentSelector } = useAgentSelector()

const createOptions = [
  {
    key: 'direct',
    label: '新建对话',
    icon: () => h(NIcon, { component: ChatbubbleOutline })
  },
  {
    key: 'group',
    label: '新建群聊',
    icon: () => h(NIcon, { component: PeopleOutline })
  }
]

function handleCreate(key) {
  openAgentSelector(key)
}
```

- [ ] **Step 4: 替换导航 pill 模板**

将模板第 56-66 行的 `<nav>` 块改为：

```html
<nav class="nav-segment">
  <NTooltip v-for="item in navItems" :key="item.key">
    <template #trigger>
      <button
        class="nav-pill"
        :class="{ active: activeKey === item.key }"
        @click="navigate(item)"
      >
        <NIcon :component="item.icon" size="18" />
      </button>
    </template>
    {{ item.tooltip }}
  </NTooltip>
</nav>
```

- [ ] **Step 5: 插入新建入口**

在 `</nav>` 和 `<div class="spacer" />` 之间（原第 66-68 行之间）插入：

```html
<NDropdown trigger="click" :options="createOptions" @select="handleCreate">
  <button class="create-btn">
    <NIcon :component="AddOutline" size="20" />
  </button>
</NDropdown>
```

- [ ] **Step 6: 添加 create-btn 样式**

在 `</style>` 前（第 165 行 `.workspace-switcher:hover` 之后）添加：

```css
.create-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: 2px solid #C6C6C8;
  background: transparent;
  color: #8E8E93;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.25, 0.1, 0.25, 1);
  padding: 0;
  flex-shrink: 0;
}
.create-btn:hover {
  border-color: #1a73e8;
  color: #1a73e8;
  background: rgba(26, 115, 232, 0.06);
}
```

- [ ] **Step 7: Commit**

```bash
git add AIagent_frontend/src/components/layout/TopBar.vue
git commit -m "feat: iconify nav pills, add create dropdown menu in TopBar"
```

---

### Task 4: 验证与测试

- [ ] **Step 1: 启动 dev server 检查编译**

```bash
cd AIagent_frontend && npx vite --host 0.0.0.0
```

确认无编译错误。

- [ ] **Step 2: 功能验证 checklist**

在浏览器中手动验证：
- [ ] 导航 pill 显示为图标（HardwareChip / Cube / Settings）
- [ ] hover pill 时出现 tooltip（Agent / 产物 / 设置）
- [ ] 点击 Agent pill 跳转到 /agents
- [ ] 点击产物 pill 跳转到 /artifacts
- [ ] 点击设置 pill 跳转到 /settings
- [ ] 点击 ⊕ 按钮弹出下拉菜单，显示"新建对话"和"新建群聊"（带图标）
- [ ] 点击"新建对话"打开 AgentSelector（direct 模式）
- [ ] 点击"新建群聊"打开 AgentSelector（group 模式）
- [ ] 再次点击 ⊕ 或点击外部关闭菜单
- [ ] ConversationSidebar 不再显示"新建对话""新建群聊"按钮
- [ ] 现有功能（工作区切换、退出、用户头像）正常工作

- [ ] **Step 3: 如发现问题，修复后 commit**

```bash
git add <fixed-files>
git commit -m "fix: toolbar redesign tweaks"
```
