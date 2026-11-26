<script setup lang="ts" generic="T extends { id: number }">
import InputText from 'primevue/inputtext'
import { computed, nextTick, ref, shallowRef, watch } from 'vue'
import { changeData, loadData } from '@/api/client'
const props = defineProps<{
  url: string
  columnTitle?: string
  columns?: { field: keyof T; title: string; format?: (row: T) => string }[]
  itemUrl?: string
  editable: boolean
  canCreate?: boolean
  title: string
  label: (row: T) => string
}>()
const emit = defineEmits<{ saved: [] }>()
const rows = shallowRef<T[]>([])
const query = ref('')
const visibleRows = computed(() => {
  const search = query.value.trim().toLocaleLowerCase('de')
  return rows.value.filter((row) =>
    props.label(row).toLocaleLowerCase('de').includes(search)
  )
})
const selected = shallowRef<T>()
const editing = ref(false)
const busy = ref(false)
const error = ref('')
const loaded = ref(false)
const loadingList = ref(true)
let loadedUrl: string | undefined
let loadVersion = 0
const list = ref<HTMLElement>()
const heading = ref<HTMLHeadingElement>()
const editor = ref<HTMLFieldSetElement>()
const createButton = ref<HTMLButtonElement>()
const retryButton = ref<HTMLButtonElement>()

function focusListAction() {
  const target = retryButton.value ?? createButton.value ?? heading.value
  target?.focus()
}

async function cancelEdit() {
  const id = selected.value?.id
  editing.value = false
  await nextTick()
  const editButton = list.value?.querySelector<HTMLButtonElement>(
    `button[data-edit-id="${id}"]`
  )
  if (editButton) editButton.focus()
  else focusListAction()
}

function isCurrentLoad(url: string, version: number) {
  return props.url === url && loadVersion === version
}

async function retryLoad(restoreFocus = false) {
  const url = props.url
  const version = ++loadVersion
  const changed = url !== loadedUrl
  error.value = ''
  loadingList.value = true
  try {
    const result = await loadData<T[]>(url)
    if (!isCurrentLoad(url, version)) return
    if (changed) query.value = ''
    rows.value = result
    loadedUrl = url
    loaded.value = true
  } catch {
    if (!isCurrentLoad(url, version)) return
    if (changed) {
      rows.value = []
      loaded.value = false
    }
    error.value = 'Liste konnte nicht geladen werden.'
  } finally {
    if (isCurrentLoad(url, version)) {
      loadingList.value = false
      if (restoreFocus) {
        await nextTick()
        if (isCurrentLoad(url, version)) focusListAction()
      }
    }
  }
}

watch(
  () => props.url,
  () => {
    selected.value = undefined
    editing.value = false
    void retryLoad()
  },
  { immediate: true }
)
async function edit(row?: T) {
  selected.value = row
  editing.value = true
  error.value = ''
  await nextTick()
  editor.value?.querySelector<HTMLInputElement>('input, select')?.focus()
}
async function save(event: Event) {
  const body = Object.fromEntries(new FormData(event.target as HTMLFormElement))
  if (!(await submit(selected.value ? 'PUT' : 'POST', body))) {
    editor.value
      ?.querySelector<HTMLButtonElement>('button[type="submit"]')
      ?.focus()
  }
}
async function remove(row: T) {
  if (!window.confirm(props.label(row) + ' wirklich löschen?')) return
  selected.value = row
  if (!(await submit('DELETE'))) {
    const deleteButton = list.value?.querySelector<HTMLButtonElement>(
      `button[data-delete-id="${row.id}"]`
    )
    deleteButton?.focus()
  }
}
async function submit(method: 'POST' | 'PUT' | 'DELETE', body?: unknown) {
  if (busy.value || loadingList.value) return false
  const source = props.url
  const version = loadVersion
  const rowId = selected.value?.id
  busy.value = true
  error.value = ''
  let saved = false
  try {
    const url =
      method === 'POST' ? source : (props.itemUrl ?? source) + '/' + rowId
    await changeData(url, method, body)
    saved = true
    if (!isCurrentLoad(source, version)) return true
    emit('saved')
    editing.value = false
    if (method === 'DELETE')
      rows.value = rows.value.filter((row) => row.id !== rowId)
    try {
      const result = await loadData<T[]>(source)
      if (isCurrentLoad(source, version)) rows.value = result
    } catch {
      if (isCurrentLoad(source, version)) {
        error.value =
          'Änderung gespeichert. Die Liste konnte nicht aktualisiert werden. Bitte erneut laden.'
      }
    }
  } catch (e) {
    if (isCurrentLoad(source, version)) {
      error.value = e instanceof Error ? e.message : 'Server nicht erreichbar.'
    }
  } finally {
    busy.value = false
  }
  await nextTick()
  if (saved && isCurrentLoad(source, version)) focusListAction()
  return saved
}
</script>
<template>
  <section ref="list">
    <header class="list-heading">
      <h1 ref="heading" tabindex="-1">{{ title }}</h1>
      <button
        v-if="!editing && loaded && (canCreate || editable)"
        ref="createButton"
        class="primary"
        :disabled="busy || loadingList"
        @click="edit()"
      >
        Neu anlegen
      </button>
    </header>

    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button
        v-if="!editing"
        ref="retryButton"
        :disabled="busy || loadingList"
        @click="retryLoad(true)"
      >
        Erneut laden
      </button>
    </div>
    <fieldset v-if="editing" ref="editor" :disabled="busy">
      <legend>
        {{ selected ? label(selected) + ' bearbeiten' : 'Neuer Eintrag' }}
      </legend>
      <form @submit.prevent="save">
        <slot name="form" :row="selected" />
        <div class="form-actions">
          <button class="primary" type="submit">
            {{ busy ? 'Wird gespeichert …' : 'Speichern' }}
          </button>
          <button type="button" @click="cancelEdit">Abbrechen</button>
        </div>
      </form>
    </fieldset>
    <template v-else>
      <div class="toolbar">
        <slot name="filter" :saving="busy" />
        <label class="search">
          <i class="pi pi-search" aria-hidden="true" />
          <InputText
            v-model="query"
            type="search"
            aria-label="Liste durchsuchen"
            placeholder="Liste durchsuchen"
          />
        </label>
      </div>
      <p v-if="!loaded && loadingList" role="status">
        Einträge werden geladen …
      </p>
      <div v-if="loaded" class="list-results">
        <div class="list-results-content" :inert="loadingList || undefined">
          <p v-if="loaded" class="count" role="status">
            {{ visibleRows.length }}
            {{ visibleRows.length === 1 ? 'Eintrag' : 'Einträge' }}
          </p>
          <p v-if="loaded && !visibleRows.length">
            {{
              rows.length
                ? 'Keine passenden Einträge gefunden.'
                : 'Keine Einträge vorhanden.'
            }}
          </p>
          <div v-if="visibleRows.length" class="register">
            <table :aria-label="title">
              <thead>
                <tr>
                  <th scope="col">{{ columnTitle ?? 'Bezeichnung' }}</th>
                  <th
                    v-for="column in columns"
                    :key="String(column.field)"
                    scope="col"
                    class="number"
                  >
                    {{ column.title }}
                  </th>
                  <th v-if="editable" scope="col" class="actions">Aktionen</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in visibleRows" :key="row.id">
                  <th scope="row">
                    <slot name="name" :row="row">{{ label(row) }}</slot>
                  </th>
                  <td
                    v-for="column in columns"
                    :key="String(column.field)"
                    class="number"
                    :data-label="column.title"
                  >
                    {{ column.format ? column.format(row) : row[column.field] }}
                  </td>
                  <td v-if="editable" class="actions">
                    <button
                      :disabled="busy"
                      :data-edit-id="row.id"
                      :aria-label="'Bearbeiten: ' + label(row)"
                      @click="edit(row)"
                    >
                      <i class="pi pi-pencil" aria-hidden="true" />
                      <span>Bearbeiten</span>
                    </button>
                    <button
                      :disabled="busy"
                      :data-delete-id="row.id"
                      :aria-label="'Löschen: ' + label(row)"
                      @click="remove(row)"
                    >
                      <i class="pi pi-trash" aria-hidden="true" />
                      <span>Löschen</span>
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <p v-if="loadingList" class="list-loading" role="status">
          Einträge werden geladen …
        </p>
      </div>
    </template>
  </section>
</template>

<style scoped>
section {
  max-width: 68rem;
}
.list-heading {
  min-height: 44px;
  flex-wrap: wrap;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;

  padding-bottom: 0;
  margin-bottom: 1.4rem;
}
h1 {
  margin: 0;
}
button {
  min-height: 44px;
  font: inherit;
  cursor: pointer;
  color: var(--accent);
  border: 1px solid var(--line);
  background: white;
  padding: 0.55rem 0.75rem;
  border-radius: 10px;
}
button:hover {
  background: #e7eef9;
}
button:disabled {
  opacity: 0.6;
  cursor: wait;
}
.primary {
  background: var(--accent);
  color: white;
  border-color: var(--accent);
  font-weight: 600;
}
.primary:hover {
  background: #174884;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 1rem;
  border-radius: 14px;
  padding: 1rem;
  border: 1px solid var(--line);
  background: white;
}
.search {
  min-width: 0;
  max-width: 100%;
  margin-inline-start: auto;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  border: 1px solid var(--line);
  padding: 0.45rem 0.65rem;
  border-radius: 10px;
  color: #4a5665;
}
.search :deep(input) {
  border: 0;
  font: inherit;
  color: var(--ink);
  min-width: 0;
  width: 13rem;
  min-height: 32px;
  padding: 0;
  box-shadow: none;
}
.search:focus-within {
  outline: 2px solid var(--accent);
}
.search :deep(input:focus) {
  outline: none;
}
.count {
  font-size: 0.85rem;
  color: #4a5665;
  margin: 1rem 0 0.5rem;
}
tbody tr:nth-child(even) {
  background: #f8f9fb;
}
@media (max-width: 600px) {
  .search {
    margin-inline-start: 0;
    flex: 1 1 100%;
  }
  .search :deep(input) {
    width: 100%;
  }
}
.list-results {
  position: relative;
}
.list-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  margin: 0;
  background: var(--paper);
  visibility: hidden;
  animation: show-list-loading 0s 160ms forwards;
}
@keyframes show-list-loading {
  to {
    visibility: visible;
  }
}
.register {
  overflow-x: auto;
  border-radius: 14px;
  background: white;
  border: 1px solid var(--line);
}
table {
  border-collapse: collapse;
  width: 100%;
  font-size: 0.94rem;
}
th,
td {
  text-align: left;
  padding: 0.85rem 0.55rem;
  border-bottom: 1px solid var(--line);
}
thead th {
  background: #f9fafb;
  font-size: 0.83rem;
  color: #45505e;
}
tbody th {
  font-weight: 600;
}
.number {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.actions {
  text-align: right;
  white-space: nowrap;
}
.actions button {
  border: 0;
  background: transparent;
}
.actions button:hover {
  background: #e6effc;
}
.actions span {
  margin-left: 0.45rem;
}
fieldset {
  border-radius: 14px;
  width: 100%;
  min-width: 0;
  max-width: 38rem;
  margin: 1.4rem 0;
  border: 1px solid var(--line);
  padding: 1.25rem;
  background: white;
}
legend {
  max-width: 100%;
  overflow-wrap: anywhere;
  font-weight: 650;
}
form,
form :deep(label) {
  display: grid;
  gap: 0.4rem;
}
form {
  gap: 1rem;
  margin-bottom: 0.5rem;
}
[role='alert'] {
  color: #972f20;
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  padding-top: 0.5rem;
}
form :deep(input),
form :deep(select) {
  width: 100%;
  min-width: 0;
  min-height: 44px;
  border-radius: 10px;
  font-size: 1rem;
}
form :deep(label) {
  min-width: 0;
}
th,
td {
  overflow-wrap: anywhere;
}
tbody tr:last-child th,
tbody tr:last-child td {
  border-bottom: 0;
}
@media (max-width: 700px) {
  .register {
    overflow: hidden;
  }
  table,
  tbody {
    display: block;
  }
  thead {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    overflow: hidden;
    clip-path: inset(50%);
  }
  tbody tr {
    display: grid;
    padding: 1rem;
    gap: 0.7rem;
    border-bottom: 1px solid var(--line);
  }
  tbody tr:last-child {
    border-bottom: 0;
  }
  tbody th,
  tbody td {
    display: block;
    padding: 0;
    border: 0;
    text-align: left;
  }
  tbody th {
    font-size: 1.05rem;
  }
  td.number {
    display: flex;
    justify-content: space-between;
    gap: 1rem;
  }
  td.number::before {
    content: attr(data-label);
    color: #45505e;
    font-variant-numeric: normal;
  }
  td.actions {
    display: flex;
    flex-wrap: wrap;
    gap: 0.5rem;
    padding-top: 0.25rem;
  }
  .actions button {
    background: #edf3fb;
    padding: 0.5rem 0.75rem;
  }
  .actions span {
    display: inline;
  }
  .form-actions > button {
    flex: 1;
  }
}
</style>
