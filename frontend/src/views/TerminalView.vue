<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ terminalId: string }>()

type ScanState = 'ready' | 'success' | 'error'

// Feste Texte, weil am Terminal keine Namen, Klassen oder Gründe stehen dürfen.
// Das Symbol zeigt das Ergebnis auch ohne Farbe an.
const STATES = {
  ready: {
    symbol: '⌾',
    headline: 'Karte auflegen',
    hint: 'Halte deine Schulkarte an das Lesegerät.'
  },
  success: {
    symbol: '✓',
    headline: 'Erfasst',
    hint: 'Deine Anwesenheit wurde gespeichert.'
  },
  error: {
    symbol: '✕',
    headline: 'Nicht erfasst',
    hint: 'Bitte wende dich an deine Lehrkraft.'
  }
}

const state = ref<ScanState>('ready')
</script>

<template>
  <div class="terminal">
    <header class="terminal__header">
      <span>Anwesenheitserfassung</span>
    </header>

    <main
      class="terminal__stage"
      :class="`terminal__stage--${state}`"
      role="status"
      aria-live="polite"
    >
      <p class="terminal__symbol" aria-hidden="true">
        {{ STATES[state].symbol }}
      </p>
      <p class="terminal__headline">{{ STATES[state].headline }}</p>
      <p class="terminal__hint">{{ STATES[state].hint }}</p>
    </main>

    <footer class="terminal__footer">
      <span>Terminal {{ terminalId }}</span>
    </footer>
  </div>
</template>
