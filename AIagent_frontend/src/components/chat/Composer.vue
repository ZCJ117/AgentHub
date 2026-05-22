<script setup>
import { ref } from 'vue'
import { NButton } from 'naive-ui'
import { useTextareaAutosize } from '@/composables/useTextareaAutosize'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  placeholder: { type: String, default: '输入消息...' }
})

const emit = defineEmits(['send', 'stop'])

const text = ref('')
const { textarea, onInput } = useTextareaAutosize()

function handleSend() {
  const val = text.value.trim()
  if (!val || props.disabled) return
  emit('send', val)
  text.value = ''
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}
</script>

<template>
  <div class="composer">
    <div class="composer-inner">
      <textarea
        ref="textarea"
        v-model="text"
        class="composer-input"
        :placeholder="placeholder"
        :disabled="disabled"
        rows="1"
        @input="onInput"
        @keydown="handleKeydown"
      />
      <NButton
        v-if="!disabled"
        type="primary"
        :disabled="!text.trim()"
        @click="handleSend"
        class="send-btn"
      >
        发送
      </NButton>
      <NButton
        v-else
        type="error"
        @click="emit('stop')"
        class="send-btn"
      >
        停止
      </NButton>
    </div>
  </div>
</template>

<style scoped>
.composer {
  padding: 16px;
  background: #FFFFFF;
  border-top: 1px solid #E5E5EA;
}

.composer-inner {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  max-width: 900px;
  margin: 0 auto;
}

.composer-input {
  flex: 1;
  resize: none;
  border: 1px solid #E5E5EA;
  border-radius: 14px;
  padding: 10px 16px;
  font-size: 14px;
  line-height: 1.5;
  font-family: inherit;
  outline: none;
  background: #F5F5F7;
  transition: border-color 0.15s;
  min-height: 42px;
  max-height: 160px;
}

.composer-input:focus {
  border-color: #2E75B6;
  background: #FFFFFF;
}

.send-btn {
  flex-shrink: 0;
  border-radius: 14px;
  height: 42px;
}
</style>
