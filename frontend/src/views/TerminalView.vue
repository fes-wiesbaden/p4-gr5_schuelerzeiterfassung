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

const SECONDS_UNTIL_READY = 3

const state = ref<ScanState>('ready')
const connected = ref(false)
const secondsLeft = ref(0)
const clock = ref(currentTime())

let events: EventSource | undefined
let clockTimer: ReturnType<typeof setInterval> | undefined
let countdownTimer: ReturnType<typeof setInterval> | undefined

function currentTime() {
  return new Date().toLocaleTimeString('de-DE', {
    hour: '2-digit',
    minute: '2-digit'
  })
}

function showResult(result: ScanState) {
  state.value = result
  secondsLeft.value = SECONDS_UNTIL_READY

  // Timer neu starten, damit auch ein zweiter Scan volle 3 Sekunden sichtbar ist.
  clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    secondsLeft.value = secondsLeft.value - 1

    if (secondsLeft.value <= 0) {
      clearInterval(countdownTimer)
      state.value = 'ready'
    }
  }, 1000)
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
  clockTimer = setInterval(() => {
    clock.value = currentTime()
  }, 1000)

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
  clearInterval(clockTimer)
  clearInterval(countdownTimer)
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

      <p v-if="state === 'ready'" class="terminal__badge">
        Bereit · {{ clock }} Uhr
      </p>
      <p v-else class="terminal__badge">
        Zurück zu „Bereit“ in {{ secondsLeft }} s
      </p>
    </main>

    <footer class="terminal__footer">
      <span>Terminal {{ terminalId }}</span>
      <span class="terminal__connection">
        {{ connected ? '● verbunden' : '● nicht verbunden' }}
      </span>
    </footer>
  </div>
</template>
