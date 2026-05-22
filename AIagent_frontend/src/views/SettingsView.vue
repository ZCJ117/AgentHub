<script setup>
import { ref, onMounted } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkspaceStore } from '@/stores/workspace'
import { NInput, NButton, NSpace, NCard } from 'naive-ui'
import { updateProfile, changePassword } from '@/api/auth'

const authStore = useAuthStore()
const workspaceStore = useWorkspaceStore()

const nickname = ref('')
const email = ref('')
const oldPassword = ref('')
const newPassword = ref('')
const savingProfile = ref(false)
const savingPassword = ref(false)
const profileMsg = ref('')
const passwordMsg = ref('')

onMounted(async () => {
  await authStore.refreshProfile()
  nickname.value = authStore.nickname || ''
})

async function saveProfile() {
  savingProfile.value = true
  profileMsg.value = ''
  try {
    await updateProfile({ nickname: nickname.value, email: email.value })
    profileMsg.value = '个人信息已更新'
    authStore.nickname = nickname.value
  } catch (err) {
    profileMsg.value = err.message || '更新失败'
  } finally {
    savingProfile.value = false
  }
}

async function savePassword() {
  if (!oldPassword.value || !newPassword.value) {
    passwordMsg.value = '请填写新旧密码'
    return
  }
  savingPassword.value = true
  passwordMsg.value = ''
  try {
    await changePassword(authStore.userId, oldPassword.value, newPassword.value)
    passwordMsg.value = '密码已修改'
    oldPassword.value = ''
    newPassword.value = ''
  } catch (err) {
    passwordMsg.value = err.message || '修改失败'
  } finally {
    savingPassword.value = false
  }
}
</script>

<template>
  <div class="settings-view">
    <h2>用户设置</h2>

    <NCard title="个人信息" class="settings-card">
      <NSpace vertical :size="12">
        <div>
          <label>用户名</label>
          <NInput :value="authStore.username" disabled />
        </div>
        <div>
          <label>昵称</label>
          <NInput v-model:value="nickname" placeholder="昵称" />
        </div>
        <div>
          <label>角色</label>
          <NInput :value="authStore.role" disabled />
        </div>
        <NButton type="primary" @click="saveProfile" :loading="savingProfile">保存</NButton>
        <span v-if="profileMsg" class="msg">{{ profileMsg }}</span>
      </NSpace>
    </NCard>

    <NCard title="修改密码" class="settings-card">
      <NSpace vertical :size="12">
        <NInput v-model:value="oldPassword" type="password" placeholder="当前密码" />
        <NInput v-model:value="newPassword" type="password" placeholder="新密码" />
        <NButton @click="savePassword" :loading="savingPassword">修改密码</NButton>
        <span v-if="passwordMsg" class="msg">{{ passwordMsg }}</span>
      </NSpace>
    </NCard>

    <NCard title="工作区" class="settings-card">
      <p>当前工作区 ID: {{ workspaceStore.activeId || '未选择' }}</p>
    </NCard>

    <NCard title="关于" class="settings-card">
      <p>AgentHub 多 Agent 协同工作平台 v1.0</p>
    </NCard>
  </div>
</template>

<style scoped>
.settings-view { padding: 24px; max-width: 640px; margin: 0 auto; }
.settings-view h2 { font-size: 22px; margin-bottom: 20px; }
.settings-card { margin-bottom: 16px; border-radius: 14px; }
label { font-size: 13px; color: #666; display: block; margin-bottom: 4px; }
.msg { font-size: 13px; color: #34C759; }
</style>
