<script setup>
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { useConversationStore } from '@/stores/conversation'
import { useChatStore } from '@/stores/chat'
import { useAgentStore } from '@/stores/agent'
import ConversationSidebar from '@/components/chat/ConversationSidebar.vue'
import ChatArea from '@/components/chat/ChatArea.vue'
import TopBar from '@/components/layout/TopBar.vue'
import DetailPanel from '@/components/layout/DetailPanel.vue'

const route = useRoute()
const convStore = useConversationStore()
const chatStore = useChatStore()
const agentStore = useAgentStore()

watch(
  () => route.params.conversationId,
  async (id) => {
    const convId = id ? Number(id) : null
    convStore.setActive(convId)
    await chatStore.initConversation(convId)
  },
  { immediate: true }
)

function handleSendMessage(text) {
  const agentId = convStore.activeConversation?.agentId || agentStore.selectedAgentId
  if (!agentId) {
    if (agentStore.agents.length > 0) {
      agentStore.selectAgent(agentStore.agents[0].id)
      chatStore.sendMessage(text, agentStore.agents[0].id)
    }
    return
  }
  chatStore.sendMessage(text, agentId)
}

function handleStopGeneration() {
  chatStore.stopGeneration()
}
</script>

<template>
  <div class="chat-view">
    <ConversationSidebar />
    <div class="chat-main">
      <TopBar />
      <ChatArea
        :messages="chatStore.messages"
        :is-streaming="chatStore.isStreaming"
        :conversation="convStore.activeConversation"
        @send="handleSendMessage"
        @stop="handleStopGeneration"
        @regenerate="chatStore.handleRegenerate"
        @reaction="chatStore.handleReaction"
      />
    </div>
    <DetailPanel />
  </div>
</template>

<style scoped>
.chat-view {
  display: flex;
  height: 100vh;
  background: #F5F5F7;
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: #FFFFFF;
}
</style>
