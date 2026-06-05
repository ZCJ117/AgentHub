<script setup>
import { NConfigProvider, NMessageProvider } from 'naive-ui'
import { themeOverrides } from '@/assets/styles/naive-theme'
import PageTransition from '@/components/common/PageTransition.vue'
</script>

<template>
  <NConfigProvider :theme-overrides="themeOverrides">
    <NMessageProvider>
      <PageTransition />
    </NMessageProvider>
  </NConfigProvider>
</template>
