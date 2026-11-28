<script lang="ts">
import type { InjectionKey, Ref } from 'vue'

export const pageErrorTarget: InjectionKey<Ref<HTMLElement | undefined>> =
  Symbol('pageErrorTarget')
</script>

<script setup lang="ts">
import { inject, ref, watch } from 'vue'

const props = defineProps<{ message: string }>()
const target = inject(pageErrorTarget, undefined)
const alert = ref<HTMLElement>()

watch(
  [alert, () => props.message, () => target?.value],
  ([element]) => {
    if (!element?.isConnected) return
    const dialog = element.closest('dialog')
    if (dialog && !dialog.open) return
    if (!dialog) window.scrollTo({ top: 0, behavior: 'instant' })
    element.focus({ preventScroll: Boolean(target?.value) })
  },
  { flush: 'post' }
)
</script>

<template>
  <Teleport :to="target ?? 'body'" :disabled="!target">
    <div
      v-if="message"
      ref="alert"
      class="page-error"
      role="alert"
      tabindex="-1"
    >
      <p>{{ message }}</p>
      <slot />
    </div>
  </Teleport>
</template>

<style scoped>
.page-error {
  display: grid;
  justify-items: start;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--danger-line);
  border-radius: 8px;
  background: var(--danger-soft);
  color: var(--danger);
  font-size: 0.9rem;
  overflow-wrap: anywhere;
}

.page-error p {
  margin: 0;
}
</style>
