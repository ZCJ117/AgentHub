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
