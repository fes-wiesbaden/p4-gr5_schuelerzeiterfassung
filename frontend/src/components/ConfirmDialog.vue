<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

import { pendingConfirm, setConfirmHost, settleConfirm } from '@/confirm'

const dialog = ref<HTMLDialogElement>()
const cancelButton = ref<HTMLButtonElement>()

watch(pendingConfirm, async (pending) => {
  const element = dialog.value
  if (!element) return
  if (!pending) {
    if (element.open) element.close()
    return
  }
  if (!element.open) element.showModal()
  await nextTick()
  // Der sichere Knopf erhält den Fokus, damit Enter nichts Folgenreiches auslöst
  cancelButton.value?.focus()
})

onMounted(() => setConfirmHost(true))
onUnmounted(() => setConfirmHost(false))
</script>

<template>
  <dialog
    ref="dialog"
    class="confirm"
    aria-labelledby="confirm-title"
    :aria-describedby="pendingConfirm?.message ? 'confirm-message' : undefined"
    @cancel.prevent="settleConfirm(false)"
    @click.self="settleConfirm(false)"
  >
    <!-- Der Innenrand liegt im Inhalt, damit nur ein Klick auf den
         Hintergrund abbricht -->
    <div v-if="pendingConfirm" class="confirm__body">
      <h2 id="confirm-title">{{ pendingConfirm.title }}</h2>
      <p v-if="pendingConfirm.message" id="confirm-message">
        {{ pendingConfirm.message }}
      </p>
      <div class="confirm__actions">
        <button ref="cancelButton" type="button" @click="settleConfirm(false)">
          Abbrechen
        </button>
        <button
          type="button"
          class="confirm__ok"
          :class="{ 'confirm__ok--danger': pendingConfirm.danger }"
          @click="settleConfirm(true)"
        >
          {{ pendingConfirm.confirmLabel ?? 'Bestätigen' }}
        </button>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.confirm {
  width: min(26rem, calc(100vw - 2rem));
  padding: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--ink);
  box-shadow: 0 24px 60px rgb(38 29 52 / 0.22);
}

.confirm__body {
  padding: 1.5rem;
}

.confirm::backdrop {
  background: rgb(38 29 52 / 0.35);
}

.confirm h2 {
  margin: 0;
  font-size: 1.2rem;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.confirm p {
  margin: 0.6rem 0 0;
  color: var(--muted);
  line-height: 1.45;
  white-space: pre-line;
}

.confirm__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 0.75rem;
  margin-top: 1.4rem;
}

.confirm button {
  min-height: 44px;
  padding: 0.55rem 1rem;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--accent);
  font: inherit;
  font-weight: 600;
  cursor: pointer;
}

.confirm button:hover {
  background: var(--accent-soft);
}

.confirm .confirm__ok {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}

.confirm .confirm__ok:hover {
  background: var(--accent-ink);
}

.confirm .confirm__ok--danger {
  border-color: #a3323c;
  background: #a3323c;
}

.confirm .confirm__ok--danger:hover {
  background: var(--danger);
}
</style>
