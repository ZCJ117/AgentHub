# Agent 输出纯文本预处理解析器 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 marked 渲染前插入逐行扫描预处理器，将 Agent 非标准纯文本转为 GFM markdown，配合 CSS 实现视觉分层

**Architecture:** 在现有 `useMarkdown.js` 中新增 `preprocessText` 函数，逐行扫描维护 in_list 状态 + 段落缓冲，输出规范 markdown；CSS 追加到 MessageBubble.vue

**Tech Stack:** JavaScript (纯函数，零依赖) + Vue 3 scoped CSS

---

### Task 1: 实现 preprocessText 并接入渲染管线

**Files:**
- Modify: `AIagent_frontend/src/composables/useMarkdown.js`

- [ ] **Step 1: 添加 preprocessText 函数**

在当前 `renderMarkdown` 函数之前（第 9 行之前）插入：

```js
// 标题候选：被空行包围、≤40字符、不以标点结尾
const TRAILING_PUNCT_RE = /[。.!！,，;；]$/
const NUMBERED_LIST_RE = /^\d+[\.\)、]\s/
const DASH_LIST_RE = /^[-*]\s/
const MARKDOWN_MARKER_RE = /^(#{1,6}\s|>\s|```|~~~|\||[-*]\s|\d+[\.\)、]\s)/

function isHeadingCandidate(line) {
  if (line.length > 40) return false
  if (TRAILING_PUNCT_RE.test(line.trimEnd())) return false
  return true
}

function isListItem(line) {
  return NUMBERED_LIST_RE.test(line) || DASH_LIST_RE.test(line)
}

function isMarkdownMarker(line) {
  return MARKDOWN_MARKER_RE.test(line)
}

export function preprocessText(text) {
  if (!text) return ''
  const lines = text.split('\n')
  const result = []
  const paragraph = []
  let inList = false
  let afterBlank = true

  function flushParagraph() {
    if (paragraph.length > 0) {
      result.push(paragraph.join('\n'))
      paragraph.length = 0
    }
  }

  function flushList() {
    if (inList) {
      result.push('')  // 空行结束列表
      inList = false
    }
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    // 空行
    if (line.trim() === '') {
      flushParagraph()
      flushList()
      result.push('')
      afterBlank = true
      continue
    }

    // 已是合法 markdown，透传
    if (isMarkdownMarker(line.trimStart())) {
      flushParagraph()
      flushList()
      result.push(line)
      afterBlank = false
      continue
    }

    // 空行后的候选标题
    if (afterBlank && isHeadingCandidate(line.trimEnd())) {
      flushParagraph()
      flushList()
      result.push('## ' + line)
      afterBlank = false
      continue
    }

    // 列表项
    if (isListItem(line)) {
      flushParagraph()
      inList = true
      result.push(line)
      afterBlank = false
      continue
    }

    // 普通文本 → 段落缓冲
    if (inList) {
      flushList()
    }
    paragraph.push(line)
    afterBlank = false
  }

  flushParagraph()
  flushList()

  return result.join('\n')
}
```

- [ ] **Step 2: 修改 renderMarkdown 函数**

将当前的 `renderMarkdown` 函数（第 9-13 行）改为：

```js
export function renderMarkdown(text) {
  if (!text) return ''
  const preprocessed = preprocessText(text)
  const raw = marked.parse(preprocessed)
  return DOMPurify.sanitize(raw)
}
```

- [ ] **Step 3: 验证构建**

```bash
cd AIagent_frontend && npx vite build --mode development
```

预期：构建成功，无编译错误。

- [ ] **Step 4: Commit**

```bash
git add AIagent_frontend/src/composables/useMarkdown.js
git commit -m "feat: add preprocessText — plain text to GFM markdown converter"
```

---

### Task 2: 追加渲染样式

**Files:**
- Modify: `AIagent_frontend/src/components/chat/MessageBubble.vue`

- [ ] **Step 1: 在 .markdown-body 区域添加 h2/ol/ul/li/p 样式**

在现有的 `.markdown-body :deep(blockquote)` 块之后（第 370 行之后）插入：

```css
.markdown-body :deep(h2) {
  font-size: 20px;
  font-weight: 700;
  color: #1D1D1F;
  margin: 20px 0 10px;
  padding-bottom: 6px;
  border-bottom: 1px solid #E5E5EA;
  line-height: 1.4;
}

.markdown-body :deep(ol) {
  margin: 8px 0;
  padding-left: 24px;
}

.markdown-body :deep(ol li) {
  margin-bottom: 4px;
  line-height: 1.6;
}

.markdown-body :deep(ul) {
  margin: 8px 0;
  padding-left: 24px;
}

.markdown-body :deep(ul li) {
  margin-bottom: 4px;
  line-height: 1.6;
}

.markdown-body :deep(p) {
  margin: 0 0 12px;
  line-height: 1.6;
}

.markdown-body :deep(p:last-child) {
  margin-bottom: 0;
}
```

- [ ] **Step 2: Commit**

```bash
git add AIagent_frontend/src/components/chat/MessageBubble.vue
git commit -m "style: add h2/ol/ul/li/p styles for preprocessed markdown output"
```

---

### Task 3: 端到端验证

- [ ] **Step 1: 构建验证**

```bash
cd AIagent_frontend && npx vite build --mode development
```

预期：构建成功，无错误。

- [ ] **Step 2: 浏览器验证 checklist**

启动 dev server 后在浏览器中测试：

- [ ] 发送一条消息让 Agent 输出包含"1. xxx"格式的数字列表，确认渲染为有序列表
- [ ] 确认"- xxx"格式渲染为无序列表
- [ ] 确认被空行包围的短标题（≤40字符、无标点结尾）渲染为 h2 + 底部分隔线
- [ ] 确认连续普通文本合并为段落，段落间有合理间距
- [ ] 确认已有的 markdown（如 ## heading、```code```、> quote）不受影响
- [ ] 确认 Agent 流式输出时逐 chunk 拼接后预处理仍正确
- [ ] 确认 DOMPurify 安全清洗不受影响（无 XSS 风险）

- [ ] **Step 3: 如有问题，修复并 commit**

```bash
git add <fixed-files>
git commit -m "fix: preprocessText edge case handling"
```
