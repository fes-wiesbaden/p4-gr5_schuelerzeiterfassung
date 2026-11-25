<script setup lang="ts">
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
type Row = { id: number; roomNumber: string }
function label(r: Row) {
  return r.roomNumber
}
</script>
<template>
  <MasterDataList url="/api/rooms" editable title="Räume" :label="label">
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
