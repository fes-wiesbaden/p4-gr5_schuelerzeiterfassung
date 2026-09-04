<script setup lang="ts">
import AppDataTable, {
  type DataTableColumn
} from '@/components/AppDataTable.vue'
import StatusBadge, {
  type AttendanceStatus
} from '@/components/StatusBadge.vue'

interface AttendanceRow {
  id: string
  name: string
  status: AttendanceStatus
  ersterScan: string
}

const columns: DataTableColumn<AttendanceRow>[] = [
  { field: 'name', header: 'Schüler' },
  { field: 'status', header: 'Status' },
  { field: 'ersterScan', header: 'Erster Scan' }
]

const rows: AttendanceRow[] = [
  { id: '1', name: 'Beispiel, Erika', status: 'anwesend', ersterScan: '07:28' },
  { id: '2', name: 'Mustermann, Tom', status: 'anwesend', ersterScan: '09:15' },
  { id: '3', name: 'Neumann, Lisa', status: 'abwesend', ersterScan: '—' },
  { id: '4', name: 'Weber, Jonas', status: 'entschuldigt', ersterScan: '—' }
]
</script>

<template>
  <section>
    <h1>Live-Anwesenheit</h1>
    <AppDataTable
      caption="Live-Anwesenheit der aktuellen Klasse"
      :columns="columns"
      :rows="rows"
      row-key="id"
    >
      <template #cell-status="{ value }">
        <StatusBadge :status="value as AttendanceStatus" />
      </template>
    </AppDataTable>
  </section>
</template>
