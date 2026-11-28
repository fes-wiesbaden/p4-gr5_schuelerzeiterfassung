<script setup lang="ts">
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
import { personLabel } from '@/api/students'
type Row = { id: number; firstName: string; lastName: string; username: string }
function label(r: Row) {
  return `${personLabel(r)} (${r.username})`
}
</script>
<template>
  <MasterDataList
    url="/api/teachers"
    editable
    can-create
    title="Lehrkräfte"
    :label="label"
  >
    <template #form="{ row }">
      <label>
        Vorname (optional)
        <InputText
          name="firstName"
          type="text"
          :default-value="row?.firstName"
          maxlength="100"
        />
      </label>
      <label>
        Nachname
        <InputText
          name="lastName"
          type="text"
          :default-value="row?.lastName"
          maxlength="100"
          required
        />
      </label>
      <label v-if="!row">
        Benutzername
        <InputText name="username" type="text" maxlength="120" required />
      </label>
      <label v-if="!row">
        Passwort
        <InputText
          name="password"
          type="password"
          autocomplete="new-password"
          required
        />
      </label>
      <label v-else>
        Neues Passwort (leer lassen = unverändert)
        <InputText
          name="password"
          type="password"
          autocomplete="new-password"
        />
      </label>
    </template>
  </MasterDataList>
</template>
