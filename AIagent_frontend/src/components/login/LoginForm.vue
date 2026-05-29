<script setup>
import { ref, computed } from 'vue'
import BlurRevealText from '@/components/common/BlurRevealText.vue'
import AppButton from '@/components/common/AppButton.vue'

const props = defineProps({
  error: { type: String, default: '' },
  loading: { type: Boolean, default: false }
})
const emit = defineEmits(['submit'])

const username = ref('')
const password = ref('')
const localStatus = ref({ text: '', type: 'info' })

const status = computed(() => {
  if (props.error) {
    return { text: props.error, type: 'error' }
  }
  return localStatus.value
})

function setStatus(text, type = 'info') {
  localStatus.value = { text, type }
}

function markInputError(el) {
  el.classList.add('error')
  setTimeout(() => el.classList.remove('error'), 1200)
}

function handleSubmit() {
  localStatus.value = { text: '', type: 'info' }

  const u = username.value.trim()
  const p = password.value

  if (!u || !p) {
    setStatus('请输入账号与密码。', 'error')
    return
  }

  emit('submit', { username: u, password: p })
}

function handleForgot(e) {
  e.preventDefault()
  setStatus('演示环境不支持重置密码，请使用 admin / admin123 登录。')
}
</script>

<template>
  <div class="card">
    <BlurRevealText as="h2" text="登录" :stagger-ms="55" />

    <form @submit.prevent="handleSubmit" autocomplete="on">
      <div class="field">
        <label for="username">账号</label>
        <input
          id="username"
          v-model="username"
          type="text"
          placeholder="输入账号"
          autocomplete="username"
        />
      </div>

      <div class="field">
        <label for="password">密码</label>
        <input
          id="password"
          v-model="password"
          type="password"
          placeholder="输入密码"
          autocomplete="current-password"
        />
      </div>

      <div
        :class="['msg', { error: status.type === 'error' }]"
        role="status"
        aria-live="polite"
      >
        {{ status.text }}
      </div>

      <AppButton variant="solid" type="submit" style="width: 100%">
        登录
      </AppButton>
    </form>

    <a class="forgot" href="#" @click="handleForgot">忘记密码？</a>

    <p class="footer">演示账号：admin / admin123</p>
  </div>
</template>

<style scoped>
.card {
  width: 100%;
  background: var(--surface);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
  padding: 52px 46px 46px;
  display: flex;
  flex-direction: column;
  gap: 26px;
  animation: slideUp 0.6s ease both;
  transition: transform 0.35s cubic-bezier(0.25, 0.1, 0.25, 1),
    box-shadow 0.35s cubic-bezier(0.25, 0.1, 0.25, 1);
}

.card:hover {
  transform: translateY(-4px);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.10);
}

.card h2 {
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.2px;
  color: var(--text);
}

.field {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-bottom: 18px;
}

.field label {
  font-size: 15px;
  font-weight: 500;
  color: var(--text);
  letter-spacing: 0.1px;
}

.field input {
  width: 100%;
  height: 54px;
  padding: 0 18px;
  border: 1.5px solid var(--border);
  border-radius: var(--radius-md);
  font-family: var(--font);
  font-size: 17px;
  color: var(--text);
  background: #fff;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
  -webkit-appearance: none;
}

.field input::placeholder {
  color: var(--text-tertiary);
}

.field input:focus {
  border-color: var(--text);
  animation: ring-glow 0.6s var(--ease-out-expo);
}

.field input.error {
  border-color: var(--error);
  box-shadow: 0 0 0 3px rgba(255, 59, 48, 0.1);
}

.msg {
  min-height: 18px;
  font-size: 13px;
  color: var(--text-secondary);
  text-align: center;
  transition: color 0.2s ease;
  margin-bottom: 8px;
}

.msg.error {
  color: var(--error);
}

.forgot {
  display: block;
  text-align: center;
  font-size: 13px;
  color: var(--text-secondary);
  text-decoration: none;
  transition: color 0.18s ease;
  margin-top: -8px;
}

.forgot:hover {
  color: var(--text);
}

.footer {
  text-align: center;
  font-size: 12px;
  color: var(--text-tertiary);
  margin-top: 4px;
}

@media (max-width: 767px) {
  .card {
    padding: 32px 26px;
  }
}
</style>
