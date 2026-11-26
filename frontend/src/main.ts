import { createApp } from 'vue'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import type { DatePickerPassThroughOptions } from 'primevue/datepicker'

import App from './App.vue'
import { germanLocale } from './germanLocale'
import { installGermanValidation } from './formValidation'
import router from './router'
import './styles.css'
import 'primeicons/primeicons.css'

const AttendanceTheme = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#f7f3fd',
      100: '#eee7fa',
      200: '#dfd2f5',
      300: '#c8b3ed',
      400: '#a68bdb',
      500: '#6344ad',
      600: '#583a9d',
      700: '#51348e',
      800: '#412973',
      900: '#321f58',
      950: '#25163e'
    }
  }
})

const calendarDate = new Intl.DateTimeFormat('de-DE', { dateStyle: 'full' })
const calendarAccessibility: DatePickerPassThroughOptions = {
  dropdown: ({ state, instance }) => ({
    'aria-controls': state.overlayVisible ? instance.panelId : null
  }),
  panel: { 'aria-modal': null },
  selectMonth: { 'aria-label': null, title: germanLocale.chooseMonth },
  selectYear: { 'aria-label': null, title: germanLocale.chooseYear },
  dayView: { 'aria-label': 'Kalendertage' },
  dayCell: ({ context }) => ({
    'aria-label': null,
    'aria-selected': context.selected
  }),
  day: ({ context: { date, today } }) => ({
    role: 'button',
    'aria-selected': null,
    'aria-current': today ? 'date' : undefined,
    'aria-label': calendarDate.format(new Date(date.year, date.month, date.day))
  }),
  month: ({ context }) => ({
    role: 'button',
    'aria-disabled': context.disabled
  }),
  year: ({ context }) => ({ role: 'button', 'aria-disabled': context.disabled })
}

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(PrimeVue, {
  locale: germanLocale,
  pt: { datepicker: calendarAccessibility },
  theme: {
    preset: AttendanceTheme,
    options: { darkModeSelector: false }
  }
})

installGermanValidation(document)
app.mount('#app')
