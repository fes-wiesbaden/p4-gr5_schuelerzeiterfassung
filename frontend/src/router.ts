import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { setUnauthorizedHandler } from '@/api/client'
import AppLayout from './layouts/AppLayout.vue'
import LoginView from './views/LoginView.vue'
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
    // Ohne Anmeldung erreichbar
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
      path: '/',
      component: AppLayout,
      children: [
        { path: '', redirect: { name: 'live-anwesenheit' } },
        {
          path: 'live-anwesenheit',
          name: 'live-anwesenheit',
          component: LiveAttendanceView
        },
        {
          path: 'klassen',
          children: [
            { path: '', name: 'klassen', component: ClassesView },
            {
              path: ':classId(\\d+)',
              name: 'klasse-schueler',
              component: StudentsView,
              props: (route) => ({ classId: Number(route.params.classId) })
            }
          ]
        },
        {
          path: 'schueler',
          children: [
            { path: '', name: 'schueler', component: StudentsView },
            {
              path: ':studentId(\\d+)',
              name: 'schueler-detail',
              component: () => import('./views/StudentDetailView.vue'),
              props: (route) => ({ studentId: Number(route.params.studentId) })
            }
          ]
        },
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

setUnauthorizedHandler(async () => {
  const auth = useAuthStore()
  const wasLoggedIn = auth.isLoggedIn
  auth.clearSession()
  const currentRoute = router.currentRoute.value
  if (wasLoggedIn && !currentRoute.meta.public) {
    await router.replace({
      name: 'login',
      query: { weiter: currentRoute.fullPath }
    })
  }
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // Prüft den Anmeldestatus vor dem Seitenwechsel, falls er noch nicht geladen wurde
  if (!auth.sessionChecked) {
    await auth.loadCurrentUser()
  }

  if (to.name === 'login') {
    return auth.isLoggedIn ? { name: 'live-anwesenheit' } : true
  }

  // Das gewünschte Ziel wird gemerkt, damit es nach der Anmeldung direkt
  // aufgerufen werden kann
  if (!auth.isLoggedIn) {
    return { name: 'login', query: { weiter: to.fullPath } }
  }

  if (!to.matched.length || (to.meta.adminOnly && !auth.isAdmin)) {
    return { name: 'live-anwesenheit' }
  }
})

export default router
