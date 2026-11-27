<script setup lang="ts">
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

defineProps<{ placeholder: string }>()
defineEmits<{ submit: [] }>()

const from = defineModel<string>('from', { required: true })
const to = defineModel<string>('to', { required: true })
const search = defineModel<string>('search', { required: true })
</script>

<template>
  <form class="log-filter" @submit.prevent="$emit('submit')">
    <label>
      <span>Von</span>
      <InputText v-model="from" type="datetime-local" required />
    </label>
    <label>
      <span>Bis</span>
      <InputText v-model="to" type="datetime-local" required />
    </label>
    <!-- Zusätzliche Filter des jeweiligen Reiters -->
    <slot />
    <label class="log-filter__search">
      <span>Suche</span>
      <InputText v-model="search" :placeholder="placeholder" maxlength="100" />
    </label>
    <Button type="submit" label="Filtern" icon="pi pi-filter" />
  </form>
</template>

<style scoped>
.log-filter {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}

.log-filter :deep(label) {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 0.85rem;
}

.log-filter__search {
  flex: 1 1 240px;
}
</style>
