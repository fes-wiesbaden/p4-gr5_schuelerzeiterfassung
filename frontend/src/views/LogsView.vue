<script setup lang="ts">
import { reactive } from 'vue'
import Column from 'primevue/column'
import DataTable, {
  type DataTablePageEvent,
  type DataTableSortEvent
} from 'primevue/datatable'
import Select from 'primevue/select'
import Tab from 'primevue/tab'
import TabList from 'primevue/tablist'
import TabPanel from 'primevue/tabpanel'
import TabPanels from 'primevue/tabpanels'
import Tabs from 'primevue/tabs'

import LogFilterForm from '@/components/LogFilterForm.vue'
import {
  STATUS_LABELS,
  berlinDate,
  berlinInstant,
  type AttendanceStatus
} from '@/api/attendance'
import {
  EVENT_LABELS,
  formatDateTime,
  loadLogPage,
  type AuditEntry,
  type RawScanEntry
} from '@/api/logs'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const PAGE_SIZE = 25

const EVENT_OPTIONS = [
  { label: 'Alle', value: '' },
  ...Object.entries(EVENT_LABELS).map(([value, label]) => ({ label, value }))
]

// Wandelt einen Wert aus datetime-local (YYYY-MM-DDTHH:MM) in einen UTC-Zeitpunkt um
function utcInstant(local: string, addMinutes = 0): string {
  const [date, time] = local.split('T')
  const at = new Date(berlinInstant(date, time))
  return new Date(at.getTime() + addMinutes * 60_000).toISOString()
}

// Filter, Sortierung und aktuelle Seite eines Reiters. Gefiltert, sortiert und
// geblättert wird im Backend, die Tabelle zeigt nur die geladene Seite.
// Blättern und Sortieren verwenden die zuletzt abgeschickten Filter, nicht
// halb ausgefüllte Eingabefelder
function logTable<T>(kind: 'raw-scans' | 'audits', defaultSort: string) {
  const weekAgo = berlinDate(new Date(Date.now() - 6 * 24 * 60 * 60 * 1000))
  const input = reactive({
    from: `${weekAgo}T00:00`,
    to: `${berlinDate(new Date())}T23:59`,
    search: '',
    eventType: ''
  })
  let applied = { ...input }
  const state = reactive({
    sortField: defaultSort,
    sortOrder: -1 as 1 | -1,
    first: 0,
    rows: [] as T[],
    total: 0,
    loading: false,
    error: '',
    // Bleibt beim Blättern stehen, solange der eingegebene Zeitraum ungültig ist
    rangeError: ''
  })
  // Nur die Antwort der neuesten Anfrage wird angezeigt
  let latest = 0

  async function load() {
    const request = ++latest
    state.loading = true
    state.error = ''
    try {
      const page = await loadLogPage<T>(kind, {
        from: utcInstant(applied.from),
        // Bis gilt einschließlich der angegebenen Minute
        to: utcInstant(applied.to, 1),
        search: applied.search.trim(),
        eventType: applied.eventType,
        sort: state.sortField,
        direction: state.sortOrder === 1 ? 'asc' : 'desc',
        page: state.first / PAGE_SIZE,
        size: PAGE_SIZE
      })
      if (request !== latest) return
      state.rows = page.items
      state.total = page.total
    } catch {
      if (request === latest) {
        state.error = 'Einträge konnten nicht geladen werden.'
      }
    } finally {
      if (request === latest) state.loading = false
    }
  }

  function filter() {
    if (!input.from || !input.to || input.from > input.to) {
      state.rangeError = 'Bitte einen gültigen Zeitraum wählen.'
      return
    }
    state.rangeError = ''
    applied = { ...input }
    state.first = 0
    load()
  }

  function onPage(event: DataTablePageEvent) {
    state.first = event.first
    load()
  }

  function onSort(event: DataTableSortEvent) {
    state.sortField = String(event.sortField ?? defaultSort)
    state.sortOrder = event.sortOrder === 1 ? 1 : -1
    state.first = 0
    load()
  }

  return { input, state, load, filter, onPage, onSort }
}

const scans = logTable<RawScanEntry>('raw-scans', 'scannedAt')
const audits = logTable<AuditEntry>('audits', 'occurredAt')
scans.load()

// Die Audit-Logs werden erst beim ersten Öffnen ihres Reiters geladen
let auditsOpened = false
function openTab(value: string | number) {
  if (value === 'audits' && !auditsOpened) {
    auditsOpened = true
    audits.load()
  }
}

function statusLabel(status: string | null): string {
  return status ? (STATUS_LABELS[status as AttendanceStatus] ?? status) : '–'
}

function minutes(entry: AuditEntry): string {
  const parts = []
  if (entry.unexcusedMinutesDelta) {
    parts.push(`unentschuldigt ${signed(entry.unexcusedMinutesDelta)}`)
  }
  if (entry.excusedMinutesDelta) {
    parts.push(`entschuldigt ${signed(entry.excusedMinutesDelta)}`)
  }
  return parts.join(', ') || '–'
}

function signed(value: number): string {
  return value > 0 ? `+${value}` : String(value)
}
</script>

<template>
  <section class="logs">
    <h1>Logs</h1>

    <Tabs value="raw-scans" @update:value="openTab">
      <TabList>
        <Tab value="raw-scans">RawScans</Tab>
        <Tab v-if="auth.isAdmin" value="audits">Audit-Logs</Tab>
      </TabList>
      <TabPanels>
        <TabPanel value="raw-scans">
          <article class="logs__card">
            <LogFilterForm
              v-model:from="scans.input.from"
              v-model:to="scans.input.to"
              v-model:search="scans.input.search"
              placeholder="Schüler, Klasse oder RFID-UID"
              @submit="scans.filter"
            />
            <p
              v-if="scans.state.rangeError || scans.state.error"
              class="logs__error"
              role="alert"
            >
              {{ scans.state.rangeError || scans.state.error }}
            </p>
            <DataTable
              :value="scans.state.rows"
              data-key="scanId"
              lazy
              paginator
              :rows="PAGE_SIZE"
              :first="scans.state.first"
              :total-records="scans.state.total"
              :loading="scans.state.loading"
              :sort-field="scans.state.sortField"
              :sort-order="scans.state.sortOrder"
              @page="scans.onPage"
              @sort="scans.onSort"
            >
              <template #empty>Keine Scans im gewählten Zeitraum.</template>
              <Column field="scannedAt" header="Zeitpunkt" sortable>
                <template #body="{ data }">
                  {{ formatDateTime(data.scannedAt) }}
                </template>
              </Column>
              <Column field="rfidUid" header="RFID-UID" sortable />
              <Column
                field="student"
                header="Schüler (aktuelle Karte)"
                sortable
              >
                <template #body="{ data }">
                  {{ data.student ?? 'Unbekannte Karte' }}
                </template>
              </Column>
              <Column field="classCode" header="Klasse" sortable>
                <template #body="{ data }">
                  {{ data.classCode ?? '–' }}
                </template>
              </Column>
            </DataTable>
          </article>
        </TabPanel>

        <TabPanel v-if="auth.isAdmin" value="audits">
          <article class="logs__card">
            <LogFilterForm
              v-model:from="audits.input.from"
              v-model:to="audits.input.to"
              v-model:search="audits.input.search"
              placeholder="Schüler, Klasse, Lehrkraft, Benutzername oder Raum"
              @submit="audits.filter"
            >
              <label>
                <span>Ereignis</span>
                <Select
                  v-model="audits.input.eventType"
                  :options="EVENT_OPTIONS"
                  option-label="label"
                  option-value="value"
                />
              </label>
            </LogFilterForm>
            <p
              v-if="audits.state.rangeError || audits.state.error"
              class="logs__error"
              role="alert"
            >
              {{ audits.state.rangeError || audits.state.error }}
            </p>
            <DataTable
              :value="audits.state.rows"
              data-key="id"
              lazy
              paginator
              :rows="PAGE_SIZE"
              :first="audits.state.first"
              :total-records="audits.state.total"
              :loading="audits.state.loading"
              :sort-field="audits.state.sortField"
              :sort-order="audits.state.sortOrder"
              @page="audits.onPage"
              @sort="audits.onSort"
            >
              <template #empty>Keine Einträge im gewählten Zeitraum.</template>
              <Column field="occurredAt" header="Zeitpunkt" sortable>
                <template #body="{ data }">
                  {{ formatDateTime(data.occurredAt) }}
                </template>
              </Column>
              <Column field="eventType" header="Ereignis" sortable>
                <template #body="{ data }">
                  {{ EVENT_LABELS[data.eventType] }}
                </template>
              </Column>
              <Column field="actor" header="Ausgelöst von" sortable />
              <Column
                field="student"
                header="Schüler (aktuelle Klasse)"
                sortable
              >
                <template #body="{ data }">
                  {{ data.student }} ({{ data.classCode }})
                </template>
              </Column>
              <Column header="Status">
                <template #body="{ data }">
                  {{ statusLabel(data.oldStatus) }} →
                  {{ statusLabel(data.newStatus) }}
                </template>
              </Column>
              <Column header="Minuten">
                <template #body="{ data }">{{ minutes(data) }}</template>
              </Column>
            </DataTable>
          </article>
        </TabPanel>
      </TabPanels>
    </Tabs>
  </section>
</template>

<style scoped>
.logs {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.logs h1 {
  margin: 0;
}

.logs__card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
  background: white;
  border: 1px solid var(--line);
  border-radius: 14px;
}

.logs__error {
  margin: 0;
  color: #b42318;
}
</style>
