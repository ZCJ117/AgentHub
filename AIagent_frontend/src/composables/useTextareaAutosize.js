import { ref } from 'vue'

/**
 * Auto-resize a textarea element on input.
 * Returns a ref to bind to the textarea, plus onInput handler and reset function.
 */
export function useTextareaAutosize() {
  const textarea = ref(null)

  function onInput() {
    const el = textarea.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 160) + 'px'
  }

  function resetHeight() {
    const el = textarea.value
    if (el) el.style.height = 'auto'
  }

  return { textarea, onInput, resetHeight }
}
