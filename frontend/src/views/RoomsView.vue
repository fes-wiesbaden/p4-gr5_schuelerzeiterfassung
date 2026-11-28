<script setup lang="ts">
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
type Row = { id: number; roomNumber: string; terminalId: number | null }
const columns = [
  {
    field: 'terminalId' as const,
    title: 'Terminal',
    format: (row: Row) => row.terminalId?.toString() ?? 'Nicht zugeordnet'
  }
]
function label(r: Row) {
  return r.roomNumber
}
</script>
<template>
  <MasterDataList
    url="/api/rooms"
    editable
    can-create
    title="Räume"
    column-title="Raumnummer"
    :label="label"
    :columns="columns"
  >
    <template #form="{ row }">
      <label>
        Raumnummer
        <InputText
          name="roomNumber"
          type="text"
          :default-value="row?.roomNumber"
          maxlength="30"
          required
        />
      </label>
    </template>
  </MasterDataList>
</template>
