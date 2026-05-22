import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import naive from 'naive-ui'
import { naiveTheme } from './assets/styles/naive-theme'
import './assets/styles/base.css'
import './assets/styles/animations.css'
import './assets/styles/transitions.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(naive, { theme: naiveTheme })
app.mount('#app')
