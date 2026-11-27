<script setup lang="ts">
defineProps<{ denied?: boolean }>()
</script>

<template>
  <section class="not-found">
    <h1>{{ denied ? 'Kein Zugriff' : 'Seite nicht gefunden' }}</h1>
    <p v-if="denied">
      Dieser Bereich ist der Verwaltung vorbehalten. Wende dich an die
      Verwaltung, wenn du hier etwas ändern möchtest.
    </p>
    <p v-else>
      Unter dieser Adresse gibt es keine Seite. Vielleicht hat sich ein
      Tippfehler eingeschlichen oder ein Lesezeichen ist veraltet.
    </p>
    <RouterLink class="not-found__link" :to="{ name: 'live-anwesenheit' }">
      <i class="pi pi-arrow-left" aria-hidden="true" />
      Zur Live-Anwesenheit
    </RouterLink>
  </section>
</template>

<style scoped>
.not-found {
  max-width: 36rem;
}

.not-found p {
  color: var(--muted);
  line-height: 1.5;
}

.not-found__link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 44px;
  color: var(--accent-ink);
  font-weight: 600;
  text-underline-offset: 3px;
}
</style>
