import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  breaks: true,
  gfm: true
})

// 标题候选：被空行包围、≤40字符、不以标点结尾
const TRAILING_PUNCT_RE = /[。.!！,，;；]$/
const NUMBERED_LIST_RE = /^\d+[\.\)、]\s/
const DASH_LIST_RE = /^[-*]\s/
const MARKDOWN_MARKER_RE = /^(#{1,6}\s|>\s|```|~~~|\|)/

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
  let afterBlank = false

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

export function renderMarkdown(text) {
  if (!text) return ''
  const preprocessed = preprocessText(text)
  const raw = marked.parse(preprocessed)
  return DOMPurify.sanitize(raw)
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

export function highlightAgentMentions(text, agentNames = []) {
  if (!text) return ''
  if (!agentNames || agentNames.length === 0) return text
  const sorted = [...agentNames].sort((a, b) => b.length - a.length)
  const escaped = sorted.map(escapeRegex)
  const pattern = new RegExp(`@(${escaped.join('|')})`, 'g')
  return text.replace(pattern, '<span class="agent-mention">$&</span>')
}
