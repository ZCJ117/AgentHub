import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/',
    redirect: '/chat'
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { requiresGuest: true }
  },
  {
    path: '/chat/:conversationId?',
    name: 'Chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/agents',
    name: 'Agents',
    component: () => import('@/views/AgentManageView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/agents/:id',
    name: 'AgentDetail',
    component: () => import('@/views/AgentDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/artifacts',
    name: 'Artifacts',
    component: () => import('@/views/ArtifactListView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/artifacts/:id',
    name: 'ArtifactDetail',
    component: () => import('@/views/ArtifactDetailView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/skills',
    name: 'Skills',
    component: () => import('@/views/SkillsView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/SettingsView.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/chat'
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  }
})

router.beforeEach((to, from, next) => {
  const auth = useAuthStore()

  if (auth.isChecking) {
    auth.checkLogin()
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return next({ name: 'Login', replace: true })
  }

  if (to.meta.requiresGuest && auth.isLoggedIn) {
    return next({ name: 'Chat', replace: true })
  }

  next()
})

export default router
