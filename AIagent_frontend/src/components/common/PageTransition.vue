<script setup>
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <router-view v-slot="{ Component }">
    <Transition name="page-fade" mode="out-in">
      <component :is="Component" :key="route.path" v-if="Component" />
    </Transition>
  </router-view>
</template>
