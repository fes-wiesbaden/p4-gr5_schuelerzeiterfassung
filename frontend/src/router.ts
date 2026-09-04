import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import AppLayout from './layouts/AppLayout.vue'
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
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/terminal/:terminalId', component: TerminalView },
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

router.beforeEach((to) => {
  const auth = useAuthStore()

  if (to.meta.adminOnly && !auth.isAdmin) {
    return { name: 'live-anwesenheit' }
  }
})

export default router
