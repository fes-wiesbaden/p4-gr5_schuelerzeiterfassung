import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

import App from './App.vue'
import router from './router'
import './styles.css'
import 'primeicons/primeicons.css'

// Aura bringt Gruen als Grundfarbe mit. Das Gestaltungskonzept nutzt das
// Dunkelblau der Seitenleiste, deshalb wird die Farbskala hier ersetzt.
const AttendanceTheme = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#f2f5f9',
      100: '#dde5ee',
      200: '#bccddf',
      300: '#92aecb',
      400: '#5b81a8',
      500: '#1e3a5c',
      600: '#1a3252',
      700: '#162a45',
      800: '#122238',
      900: '#0e1a2b',
      950: '#0a121e'
    }
  }
})

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(PrimeVue, {
  theme: {
    preset: AttendanceTheme,
    options: { darkModeSelector: false }
  }
})

app.mount('#app')
