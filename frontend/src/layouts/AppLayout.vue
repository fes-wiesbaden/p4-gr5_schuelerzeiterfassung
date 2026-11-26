<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'

import AttendanceAssistant from '@/components/AttendanceAssistant.vue'
import { useAuthStore } from '@/stores/auth'
import { administrationNavItems, mainNavItems } from './navigation'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const sidebarOpen = ref(false)
const menuButton = ref<HTMLButtonElement>()
const pageContent = ref<HTMLElement>()

const roleLabel = computed(() => {
  switch (auth.user?.role) {
    case 'admin':
      return 'Administrator'
    case 'lehrkraft':
      return 'Lehrkraft'
    default:
      return ''
  }
})

watch(
  () => route.path,
  async () => {
    sidebarOpen.value = false
    await nextTick()
    pageContent.value?.focus()
  }
)

function currentSection(routeName: string) {
  if (route.name === routeName) return 'page'
  if (routeName === 'schueler' && route.name === 'schueler-detail')
    return 'location'
  if (routeName === 'klassen' && route.name === 'klasse-schueler')
    return 'location'
}

function revealFocus(event: FocusEvent) {
  const target = event.target
  if (!(target instanceof HTMLElement) || !target.matches(':focus-visible'))
    return
  const launcher = document.getElementById('klassenlotse-oeffnen')
  if (!launcher) return
  const focus = target.getBoundingClientRect()
  const bubble = launcher.getBoundingClientRect()
  if (
    focus.left < bubble.right &&
    focus.right > bubble.left &&
    focus.top < bubble.bottom &&
    focus.bottom > bubble.top
  ) {
    target.scrollIntoView({ block: 'center', inline: 'nearest' })
  }
}

async function handleLogout() {
  if (await auth.logout()) {
    await router.replace({ name: 'login' })
  }
}

function handleSidebarKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    sidebarOpen.value = false
    menuButton.value?.focus()
  }
}
</script>

<template>
  <div class="app-shell">
    <a
      class="app-skip-link"
      href="#app-main"
      @click.prevent="pageContent?.focus()"
    >
      Zum Hauptinhalt
    </a>
    <header class="app-header">
      <div class="app-header__start">
        <button
          ref="menuButton"
          type="button"
          class="app-header__menu-toggle"
          :aria-label="
            sidebarOpen ? 'Navigation schließen' : 'Navigation öffnen'
          "
          :aria-expanded="sidebarOpen"
          aria-controls="app-sidebar"
          @click="sidebarOpen = !sidebarOpen"
        >
          <i class="pi pi-bars" aria-hidden="true" />
        </button>
        <RouterLink
          class="app-header__title"
          :to="{ name: 'live-anwesenheit' }"
        >
          <i class="brand-mark pi pi-id-card" aria-hidden="true" />
          <span>Anwesenheitserfassung</span>
        </RouterLink>
      </div>
      <div class="app-header__user">
        <span class="app-header__user-info">
          {{ auth.user?.name }}
          <span class="app-header__user-role">· {{ roleLabel }}</span>
        </span>
        <Button
          label="Abmelden"
          size="small"
          outlined
          :loading="auth.busy"
          @click="handleLogout"
        />
      </div>
    </header>

    <div class="app-body">
      <aside
        id="app-sidebar"
        class="app-sidebar"
        :class="{ 'app-sidebar--open': sidebarOpen }"
        @keydown="handleSidebarKeydown"
      >
        <nav aria-label="Hauptnavigation">
          <template
            v-for="(items, index) in auth.isAdmin
              ? [mainNavItems, administrationNavItems]
              : [mainNavItems]"
            :key="index"
          >
            <p v-if="index === 1" class="app-nav__section-label">
              Administration
            </p>
            <ul class="app-nav">
              <li v-for="item in items" :key="item.routeName">
                <RouterLink
                  :to="{ name: item.routeName }"
                  class="app-nav__link"
                  :class="{
                    'app-nav__link--active': currentSection(item.routeName)
                  }"
                  :aria-current="currentSection(item.routeName)"
                  active-class="app-nav__link--active"
                >
                  <i class="pi" :class="item.icon" aria-hidden="true" />
                  <span>{{ item.label }}</span>
                </RouterLink>
              </li>
            </ul>
          </template>
        </nav>
      </aside>

      <main
        id="app-main"
        ref="pageContent"
        class="app-content"
        tabindex="-1"
        @focusin="revealFocus"
      >
        <p v-if="auth.errorMessage" class="app-content__error" role="alert">
          {{ auth.errorMessage }}
        </p>
        <div class="app-content__inner"><RouterView /></div>
      </main>
    </div>
    <AttendanceAssistant />
  </div>
</template>

<style scoped>
.app-shell {
  display: grid;
  grid-template-rows: auto 1fr;
  min-height: 100svh;
}

.app-skip-link {
  position: fixed;
  z-index: 20;
  inset-block-start: 10px;
  inset-inline-start: 12px;
  min-height: 44px;
  padding: 11px 16px;
  border-radius: 8px;
  background: var(--surface);
  color: var(--accent-ink);
  font-weight: 650;
}

.app-skip-link:not(:focus) {
  clip-path: inset(50%);
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 66px;
  padding: 10px clamp(16px, 3vw, 32px);
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}

.app-header__start,
.app-header__user {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.app-header__menu-toggle {
  display: none;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: 1px solid var(--control-border);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink);
  cursor: pointer;
}

.app-header__title {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: var(--ink);
  font-weight: 650;
  text-decoration: none;
  overflow-wrap: anywhere;
}

.app-header__user-info {
  color: var(--ink);
  font-size: 0.875rem;
  overflow-wrap: anywhere;
}

.app-header__user-role {
  color: var(--muted);
}

.app-header__user :deep(button) {
  flex: none;
  min-height: 40px;
}

.app-body {
  display: grid;
  grid-template-columns: 228px minmax(0, 1fr);
  min-height: 0;
}

.app-sidebar {
  padding: 24px 12px;
  border-inline-end: 1px solid var(--line);
  background: var(--surface);
}

.app-nav {
  display: grid;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.app-nav__link {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 44px;
  padding: 10px 12px;
  border-radius: 10px;
  color: var(--muted);
  text-decoration: none;
  font-size: 0.875rem;
}

.app-nav__link:hover {
  background: var(--paper);
  color: var(--ink);
}

.app-nav__link:focus-visible,
.app-header__menu-toggle:focus-visible {
  outline-offset: -2px;
}

.app-nav__link--active,
.app-nav__link--active:hover {
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-weight: 650;
}

.app-nav__section-label {
  margin: 24px 12px 7px;
  color: var(--muted);
  font-size: 0.75rem;
  font-weight: 650;
}

.app-content {
  min-width: 0;
  padding: clamp(24px, 5vw, 66px) clamp(16px, 4vw, 56px);
  padding-bottom: max(5rem, env(safe-area-inset-bottom));
}

.app-content__inner {
  max-width: 1160px;
  margin-inline: auto;
}

.app-content__error {
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff2f2;
  color: #8c3038;
}

@media (max-width: 960px) {
  .app-header__menu-toggle {
    display: inline-flex;
  }

  .app-body {
    grid-template-columns: minmax(0, 1fr);
    align-content: start;
  }

  .app-sidebar {
    display: none;
    border-inline-end: 0;
    border-bottom: 1px solid var(--line);
    padding: 12px 16px;
  }

  .app-sidebar--open {
    display: block;
  }

  .app-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .app-header {
    flex-wrap: wrap;
    gap: 9px;
  }

  .app-header__start,
  .app-header__user {
    width: 100%;
  }

  .app-header__user {
    justify-content: space-between;
  }
}

@media (max-width: 380px) {
  .app-nav {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
