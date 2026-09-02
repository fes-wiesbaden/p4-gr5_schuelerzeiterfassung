<script setup lang="ts">
defineProps<{ terminalId: string }>()
</script>

<template>
  <div class="terminal">
    <header class="terminal__header">
      <span>Anwesenheitserfassung</span>
    </header>

    <main class="terminal__stage" role="status" aria-live="polite">
      <p class="terminal__symbol" aria-hidden="true">⌾</p>
      <p class="terminal__headline">Karte auflegen</p>
      <p class="terminal__hint">Halte deine Schulkarte an das Lesegerät.</p>
    </main>

    <footer class="terminal__footer">
      <span>Terminal {{ terminalId }}</span>
    </footer>
  </div>
</template>
