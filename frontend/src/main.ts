import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'

import App from './App.vue'
import router from './router'
import './styles.css'
import 'primeicons/primeicons.css'

// Verwendet für die Primärfarbe dieselbe dunkelblaue Farbskala wie für die Seitenleiste
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
  locale: {
    firstDayOfWeek: 1,
    dayNames: [
      'Sonntag',
      'Montag',
      'Dienstag',
      'Mittwoch',
      'Donnerstag',
      'Freitag',
      'Samstag'
    ],
    dayNamesMin: ['So', 'Mo', 'Di', 'Mi', 'Do', 'Fr', 'Sa'],
    monthNames: [
      'Januar',
      'Februar',
      'März',
      'April',
      'Mai',
      'Juni',
      'Juli',
      'August',
      'September',
      'Oktober',
      'November',
      'Dezember'
    ],
    monthNamesShort: [
      'Jan',
      'Feb',
      'Mär',
      'Apr',
      'Mai',
      'Jun',
      'Jul',
      'Aug',
      'Sep',
      'Okt',
      'Nov',
      'Dez'
    ],
    weekHeader: 'KW',
    chooseDate: 'Datum auswählen',
    chooseMonth: 'Monat auswählen',
    chooseYear: 'Jahr auswählen',
    prevMonth: 'Vorheriger Monat',
    nextMonth: 'Nächster Monat',
    prevYear: 'Vorheriges Jahr',
    nextYear: 'Nächstes Jahr'
  },
  theme: {
    preset: AttendanceTheme,
    options: { darkModeSelector: false }
  }
})

app.mount('#app')
