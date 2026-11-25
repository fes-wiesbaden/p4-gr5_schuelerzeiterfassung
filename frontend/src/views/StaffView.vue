<script setup lang="ts">
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
type Row = { id: number; firstName: string; lastName: string; username: string }
function label(r: Row) {
  return r.lastName + ', ' + r.firstName + ' (' + r.username + ')'
}
</script>
<template>
  <MasterDataList
    url="/api/teachers"
    editable
    title="Lehrkräfte"
    :label="label"
  >
    <template #form="{ row }">
      <label>
        Vorname
        <InputText
          name="firstName"
          type="text"
          :default-value="row?.firstName"
          maxlength="100"
          required
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
        <InputText
          name="username"
          type="text"
          :default-value="row?.username"
          maxlength="120"
          required
        />
      </label>
      <label v-if="!row">
        Passwort
        <InputText name="password" type="password" required />
      </label>
    </template>
  </MasterDataList>
</template>
