<script setup>
import { NConfigProvider } from 'naive-ui'
import { themeOverrides } from '@/assets/styles/naive-theme'
import PageTransition from '@/components/common/PageTransition.vue'
</script>

<template>
  <NConfigProvider :theme-overrides="themeOverrides">
    <PageTransition />
  </NConfigProvider>
</template>
