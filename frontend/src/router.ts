import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '@/stores/auth'
import { setUnauthorizedHandler } from '@/api/client'
import AppLayout from './layouts/AppLayout.vue'
import LoginView from './views/LoginView.vue'
import LiveAttendanceView from './views/LiveAttendanceView.vue'
import ClassesView from './views/ClassesView.vue'
import StudentsView from './views/StudentsView.vue'
import RoomsView from './views/RoomsView.vue'
import StaffView from './views/StaffView.vue'
import NotFoundView from './views/NotFoundView.vue'

declare module 'vue-router' {
  interface RouteMeta {
    adminOnly?: boolean
    title?: string
    // Ohne Anmeldung erreichbar
    public?: boolean
  }
}

const router = createRouter({
  history: createWebHistory(),
  // Neue Seiten beginnen oben; „Zurück“ stellt die alte Position wieder her
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.path === from.path) return undefined
    return { top: 0 }
  },
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { public: true, title: 'Anmelden' }
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', redirect: { name: 'live-anwesenheit' } },
        {
          path: 'live-anwesenheit',
          name: 'live-anwesenheit',
          component: LiveAttendanceView,
          meta: { title: 'Live-Anwesenheit' }
        },
        {
          path: 'klassen',
          children: [
            {
              path: '',
              name: 'klassen',
              component: ClassesView,
              meta: { title: 'Klassen' }
            },
            {
              path: ':classId(\\d+)',
              name: 'klasse-schueler',
              component: StudentsView,
              meta: { title: 'Klassenliste' },
              props: (route) => ({ classId: Number(route.params.classId) })
            }
          ]
        },
        {
          path: 'schueler',
          children: [
            {
              path: '',
              name: 'schueler',
              component: StudentsView,
              meta: { title: 'Schüler' }
            },
            {
              path: ':studentId(\\d+)',
              name: 'schueler-detail',
              component: () => import('./views/StudentDetailView.vue'),
              meta: { title: 'Schülerprofil' },
              props: (route) => ({ studentId: Number(route.params.studentId) })
            }
          ]
        },
        {
          path: 'planung',
          name: 'planung',
          component: () => import('./views/SchedulePlanningView.vue'),
          meta: { title: 'Stunden- und Blockplan' }
        },
        {
          path: 'planung/entwurf',
          name: 'stundenplan-entwurf',
          component: () => import('./views/TimetableImportView.vue'),
          meta: { adminOnly: true, title: 'Stundenplan-Entwurf' }
        },
        { path: 'auswertungen', redirect: { name: 'schueler' } },
        {
          path: 'raeume',
          name: 'raeume',
          component: RoomsView,
          meta: { adminOnly: true, title: 'Räume' }
        },
        {
          path: 'terminals',
          redirect: { name: 'raeume' }
        },
        {
          path: 'personal',
          name: 'personal',
          component: StaffView,
          meta: { adminOnly: true, title: 'Lehrkräfte' }
        },
        {
          path: 'kein-zugriff',
          name: 'kein-zugriff',
          component: NotFoundView,
          props: { denied: true },
          meta: { title: 'Kein Zugriff' }
        },
        {
          path: ':pathMatch(.*)*',
          name: 'nicht-gefunden',
          component: NotFoundView,
          meta: { title: 'Seite nicht gefunden' }
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
      query: { weiter: currentRoute.fullPath, grund: 'abgelaufen' }
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

  if (to.meta.adminOnly && !auth.isAdmin) {
    return { name: 'kein-zugriff' }
  }
})

router.afterEach((to, _from, failure) => {
  if (failure) return
  document.title = to.meta.title
    ? `${to.meta.title} · Anwesenheitserfassung`
    : 'Anwesenheitserfassung'
})

export default router
