# Agent 输出纯文本预处理解析器 — 设计文档

日期: 2026-06-03 | 状态: 待评审

---

## 目标

在浏览器端编写一个纯文本预处理器，将 Agent 输出的非标准纯文本自动转换为规范 GFM markdown，再交由 marked 渲染，配合 CSS 实现视觉分层。

## 改动范围

| 文件 | 改动量 | 说明 |
|---|---|---|
| `AIagent_frontend/src/composables/useMarkdown.js` | ~60 行 | 新增 `preprocessText` 函数；修改 `renderMarkdown` 调用链 |
| `AIagent_frontend/src/components/chat/MessageBubble.vue` | ~40 行 CSS | 追加 h2/ol/ul/li/p 样式 |
| 不新增文件 | — | — |

## 渲染管线

```
纯文本 → preprocessText() → marked.parse() → DOMPurify → v-html
```

## 预处理算法

逐行扫描状态机，维护 `in_list` 标志和段落缓冲。

### 识别规则

| 模式 | 判定条件 | 输出 |
|---|---|---|
| 标题 (h2) | 空行包围、≤40 字符、不以 `。.!！,，;；` 结尾 | `## line` |
| 有序列表 | 行首匹配 `/^\d+[\.\)、]\s/` | 原样透传（marked 可识别） |
| 无序列表 | 行首匹配 `/^[-*]\s/` | 原样透传（marked 可识别） |
| 段落 | 连续的非特殊行 | 合并为段落，空行分隔 |

### 透传规则

行首已是合法 markdown 标记（`#`、`>`、`\`\`\``、`|`、`- `、数字列表等），原样输出不做二次处理。

### 伪代码

```
preprocessText(text):
  lines = text.split('\n')
  result = []
  paragraph = []
  in_list = false

  for each line:
    if line is blank:
      flush paragraph, flush list
      in_list = false
      result.push('')
    elif after_blank AND is_heading_candidate(line):
      result.push('## ' + line)
    elif matches numbered_list OR dash_list:
      in_list = true
      result.push(line)
    else:
      if in_list: flush list, in_list = false
      paragraph.push(line)

  flush remaining
  return result.join('\n')
```

## CSS 样式

```css
.markdown-body :deep(h2) {
  font-size: 20px; font-weight: 700; color: #1D1D1F;
  margin: 20px 0 10px; padding-bottom: 6px;
  border-bottom: 1px solid #E5E5EA; line-height: 1.4;
}
.markdown-body :deep(ol)      { margin: 8px 0; padding-left: 24px; }
.markdown-body :deep(ol li)   { margin-bottom: 4px; line-height: 1.6; }
.markdown-body :deep(ul)      { margin: 8px 0; padding-left: 24px; }
.markdown-body :deep(ul li)   { margin-bottom: 4px; line-height: 1.6; }
.markdown-body :deep(p)       { margin: 0 0 12px; line-height: 1.6; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
```

## 视觉分层

```
h2 — 20px bold + 底部分隔线 (最突出)
ol / ul — 缩进列表 (次层级)
p  — 16px 正文段落 (基础层)
```

## 不变项

- `marked` 库保留，GFM 模式不变
- `DOMPurify` 安全清洗不变
- `highlightAgentMentions` 函数不变（在预处理前执行）
- 已有的 code/pre/blockquote 样式不变
- SSE 流式传输逻辑不变
