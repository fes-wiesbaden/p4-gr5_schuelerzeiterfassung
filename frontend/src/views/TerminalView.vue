<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

const props = defineProps<{ terminalId: string }>()

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
const connected = ref(false)

let events: EventSource | undefined

function showResult(result: ScanState) {
  state.value = result
}

// Kaputte Nachrichten einfach ignorieren, sonst bleibt die Anzeige hängen.
function handleScan(event: MessageEvent<string>) {
  let result

  try {
    result = JSON.parse(event.data).result
  } catch {
    return
  }

  if (result === 'VERARBEITET') {
    showResult('success')
  }

  if (result === 'ABGELEHNT') {
    showResult('error')
  }
}

onMounted(() => {
  events = new EventSource(`/api/terminals/${props.terminalId}/events`)
  events.addEventListener('scan', handleScan)
  events.addEventListener('open', () => {
    connected.value = true
  })
  events.addEventListener('error', () => {
    connected.value = false
  })
})

onUnmounted(() => {
  events?.close()
})
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
      <span class="terminal__connection">
        {{ connected ? '● verbunden' : '● nicht verbunden' }}
      </span>
    </footer>
  </div>
</template>
