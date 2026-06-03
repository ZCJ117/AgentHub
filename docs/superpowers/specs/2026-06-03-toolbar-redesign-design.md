# TopBar 工具栏图标化 & 新建入口整合 — 设计文档

日期: 2026-06-03 | 状态: 待评审

---

## 目标

1. 顶部导航 pill 从纯文字改为纯图标 + tooltip
2. 新建入口从 ConversationSidebar 的两个独立按钮整合为 TopBar 中的圆形 + 号下拉菜单

## 改动范围

| 文件 | 改动量 | 说明 |
|---|---|---|
| `TopBar.vue` | ~30 行 | navItems 扩展为图标模式；添加 NDropdown 新建菜单；新增 provide |
| `ConversationSidebar.vue` | ~10 行 | 移除两个新建按钮；新增 inject；改为调用注入的方法 |
| 不新增文件 | — | — |

## 详细设计

### 1. 导航 Pill 图标化 (TopBar.vue)

**navItems 从文字数组扩展为图标数组：**

```js
import { HardwareChipOutline, CubeOutline, SettingsOutline } from '@vicons/ionicons5'

const navItems = [
  { key: 'agents',    icon: HardwareChipOutline, tooltip: 'Agent',   path: '/agents' },
  { key: 'artifacts', icon: CubeOutline,         tooltip: '产物',    path: '/artifacts' },
  { key: 'settings',  icon: SettingsOutline,     tooltip: '设置',    path: '/settings' }
]
```

**模板改为 NTooltip + NIcon 渲染：**

```html
<NTooltip v-for="item in navItems" :key="item.key">
  <template #trigger>
    <button class="nav-pill" :class="{ active: activeKey === item.key }" @click="navigate(item)">
      <NIcon :component="item.icon" size="18" />
    </button>
  </template>
  {{ item.tooltip }}
</NTooltip>
```

- 图标 18px，NTooltip hover 触发
- 沿用现有 `.nav-pill` 和 `.nav-segment` 样式，active 状态不变

### 2. 新建入口悬浮菜单 (TopBar.vue)

**在 spacer 左侧插入圆形 + 号 NDropdown：**

```html
<NDropdown trigger="click" :options="createOptions" @select="handleCreate">
  <button class="create-btn">
    <NIcon :component="AddOutline" size="20" />
  </button>
</NDropdown>
```

**Script：**

```js
import { AddOutline, ChatbubbleOutline, PeopleOutline } from '@vicons/ionicons5'

const createOptions = [
  { key: 'direct', label: '新建对话', icon: () => h(NIcon, { component: ChatbubbleOutline }) },
  { key: 'group',  label: '新建群聊', icon: () => h(NIcon, { component: PeopleOutline }) }
]

function handleCreate(key) {
  openAgentSelector(key)
}
```

**CSS：**

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
  transition: all 0.2s;
  padding: 0;
}
.create-btn:hover {
  border-color: #1a73e8;
  color: #1a73e8;
  background: rgba(26, 115, 232, 0.06);
}
```

### 3. 通信机制：provide/inject

TopBar 与 ConversationSidebar 之间没有父子关系，通过 provide/inject 传递 AgentSelector 打开方法：

**ConversationSidebar.vue：**

```js
import { provide } from 'vue'

const showAgentSelector = ref(false)
const selectorMode = ref('direct')

function openAgentSelector(mode) {
  selectorMode.value = mode
  showAgentSelector.value = true
}

provide('openAgentSelector', openAgentSelector)
```

**TopBar.vue：**

```js
import { inject } from 'vue'

const openAgentSelector = inject('openAgentSelector')
```

### 4. ConversationSidebar 清理

移除第 119-125 行的两个 NButton：

```html
<!-- 删除以下内容 -->
<NButton type="primary" block @click="selectorMode = 'direct'; showAgentSelector = true">+ 新建对话</NButton>
<NButton block @click="selectorMode = 'group'; showAgentSelector = true">+ 新建群聊</NButton>
```

移除不再需要的 import：`selectorMode` 和 `showAgentSelector` 的模板内联逻辑删除，但变量保留用于 AgentSelector 绑定。

## 图标清单

| 用途 | 图标组件 | 来源 |
|---|---|---|
| Agent pill | `HardwareChipOutline` | `@vicons/ionicons5` |
| 产物 pill | `CubeOutline` | `@vicons/ionicons5` |
| 设置 pill | `SettingsOutline` | `@vicons/ionicons5` |
| 新建对话 | `ChatbubbleOutline` | `@vicons/ionicons5` |
| 新建群聊 | `PeopleOutline` | `@vicons/ionicons5` |
| 加号按钮 | `AddOutline` | `@vicons/ionicons5` |

## 不变项

- "产物"和"设置"仍通过路由跳转
- "退出"按钮保持不变（文字按钮）
- 工作区切换器保持不变
- UserPill 保持不变
- AgentSelector 组件逻辑不变
- 路由和全局状态不变
