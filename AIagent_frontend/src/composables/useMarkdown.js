import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  breaks: true,
  gfm: true
})

export function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text)
  return DOMPurify.sanitize(raw)
}

const AGENT_MENTION_RE = /@([^\s,，。；;:：\n]+)/g

export function highlightAgentMentions(text) {
  if (!text) return ''
  return text.replace(AGENT_MENTION_RE, '<span class="agent-mention">$&</span>')
}
