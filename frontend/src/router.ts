import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import AppLayout from './layouts/AppLayout.vue'
import LoginView from './views/LoginView.vue'
import TerminalView from './views/TerminalView.vue'
import LiveAttendanceView from './views/LiveAttendanceView.vue'
import ClassesView from './views/ClassesView.vue'
import StudentsView from './views/StudentsView.vue'
import SchedulePlanningView from './views/SchedulePlanningView.vue'
import ReportsView from './views/ReportsView.vue'
import RoomsView from './views/RoomsView.vue'
import TerminalsAdminView from './views/TerminalsAdminView.vue'
import StaffView from './views/StaffView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    adminOnly?: boolean
    // Ohne Anmeldung erreichbar.
    public?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true }
    },
    {
      path: '/terminal/:terminalId',
      component: TerminalView,
      meta: { public: true }
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', redirect: { name: 'live-anwesenheit' } },
        {
          path: 'live-anwesenheit',
          name: 'live-anwesenheit',
          component: LiveAttendanceView
        },
        { path: 'klassen', name: 'klassen', component: ClassesView },
        { path: 'schueler', name: 'schueler', component: StudentsView },
        { path: 'planung', name: 'planung', component: SchedulePlanningView },
        { path: 'auswertungen', name: 'auswertungen', component: ReportsView },
        {
          path: 'raeume',
          name: 'raeume',
          component: RoomsView,
          meta: { adminOnly: true }
        },
        {
          path: 'terminals',
          name: 'terminals',
          component: TerminalsAdminView,
          meta: { adminOnly: true }
        },
        {
          path: 'personal',
          name: 'personal',
          component: StaffView,
          meta: { adminOnly: true }
        }
      ]
    }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // Nach einem Neuladen ist der Store leer, das Sitzungscookie kann aber noch
  // gelten. Einmal pro Sitzung beim Backend nachfragen.
  if (!auth.sessionChecked) {
    await auth.loadCurrentUser()
  }

  if (to.name === 'login') {
    return auth.isLoggedIn ? { name: 'live-anwesenheit' } : true
  }

  if (to.meta.public) {
    return true
  }

  // Das gewuenschte Ziel wird gemerkt, damit es nach der Anmeldung direkt
  // aufgerufen werden kann.
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { weiter: to.fullPath } }
  }

  if (to.meta.adminOnly && !auth.isAdmin) {
    return { name: 'live-anwesenheit' }
  }
})

export default router
