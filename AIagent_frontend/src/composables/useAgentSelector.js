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
