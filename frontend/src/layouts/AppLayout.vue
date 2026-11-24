<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'

import { useAuthStore } from '@/stores/auth'
import { administrationNavItems, mainNavItems } from './navigation'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const sidebarOpen = ref(false)
const menuButton = ref<HTMLButtonElement>()

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
  () => route.fullPath,
  () => {
    sidebarOpen.value = false
  }
)

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
        <span class="app-header__title">RFID-Anwesenheitserfassung</span>
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

      <main class="app-content">
        <p v-if="auth.errorMessage" class="app-content__error" role="alert">
          {{ auth.errorMessage }}
        </p>
        <RouterView />
      </main>
    </div>
  </div>
</template>

<style scoped>
.app-shell {
  --app-header-height: 64px;

  display: grid;
  grid-template-rows: auto 1fr;
  min-height: 100vh;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: var(--app-header-height);
  padding: 0 24px;
  background: #171b21;
  color: white;
  border-bottom: 1px solid #e2e5ea;
}

.app-header__start {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.app-header__menu-toggle {
  display: none;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: 1px solid #d7dce3;
  border-radius: 10px;
  background: #ffffff;
  color: var(--ink);
  font-size: 1rem;
  cursor: pointer;
}

.app-header__title {
  overflow-wrap: anywhere;
  font-weight: 700;
  font-size: 1.05rem;
  color: white;
}

.app-header__user {
  display: flex;
  align-items: center;
  gap: 16px;
}

.app-header__user :deep(button) {
  min-height: 44px;
  border-radius: 10px;
  color: white;
  border-color: #737f91;
  background: transparent;
}
.app-header__user :deep(button:hover) {
  background: #353d49;
}
.app-header__user-info {
  font-size: 0.9rem;
  color: white;
}

.app-header__user-role {
  color: #c2cad5;
}

.app-body {
  display: grid;
  grid-template-columns: 220px 1fr;
  min-height: 0;
}

.app-sidebar {
  background: #252a32;
  border-right: 1px solid var(--line);
  padding: 20px 10px;
}

.app-nav {
  list-style: none;
  margin: 0;
  padding: 0;
}

.app-nav__link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  min-height: 44px;
  border-radius: 10px;
  margin-bottom: 4px;
  color: #e0e4ea;
  text-decoration: none;
  font-size: 0.9rem;
}

.app-nav__link:hover {
  background: #383f49;
  color: white;
}

.app-nav__link:focus-visible {
  outline: 2px solid #ffffff;
  outline-offset: -2px;
}

.app-header__menu-toggle:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: -3px;
}

.app-nav__link--active {
  background: var(--accent);

  color: white;
  font-weight: 600;
}

.app-nav__section-label {
  margin: 20px 0 4px;
  padding: 0 20px;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: #bbc2cc;
}

.app-content {
  padding: clamp(1rem, 4vw, 3.5rem);
  background: var(--paper);
  min-width: 0;
}

.app-content__error {
  margin: 0 0 16px;
  padding: 10px 12px;
  border: 1px solid #f0b4b4;
  border-radius: 10px;
  background: #fdf2f2;
  color: #a12525;
}

@media (max-width: 960px) {
  .app-header {
    flex-wrap: wrap;
    gap: 0.75rem;
    padding: 12px 16px;
  }
  .app-header__menu-toggle {
    display: inline-flex;
    flex-shrink: 0;
  }
  .app-header__user {
    margin-left: auto;
  }
  .app-body {
    grid-template-columns: minmax(0, 1fr);
    align-content: start;
  }
  .app-sidebar {
    display: none;
    border: 0;
    padding: 12px 16px;
  }
  .app-sidebar--open {
    display: block;
  }
  .app-nav {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 4px 12px;
  }
  .app-nav__link {
    height: 100%;
  }
  .app-content {
    padding: 24px;
  }
}
@media (max-width: 600px) {
  .app-header__start {
    width: 100%;
  }
  .app-header__title {
    font-size: 1rem;
  }
  .app-header__user {
    width: 100%;
    justify-content: space-between;
    gap: 12px;
  }
  .app-header__user-info {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  .app-header__user-role {
    display: block;
  }
  .app-content {
    padding: 24px 16px;
  }
  .app-nav {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
