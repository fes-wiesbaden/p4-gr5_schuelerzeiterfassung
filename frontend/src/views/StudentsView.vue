<script setup lang="ts">
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
import { ref, onMounted, computed } from 'vue'
import { loadData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
type Option = { id: number; name: string }
type Options = { staffId: number; blockPlans: Option[]; timetables: Option[] }
const options = ref<Options>()
const error = ref('')
const classes = ref<
  { id: number; classCode: string; classTeacherId: number }[]
>([])
const selected = ref<number>()
const own = computed(
  () =>
    classes.value.find((c) => c.id === selected.value)?.classTeacherId ===
    options.value?.staffId
)
onMounted(async () => {
  try {
    options.value = await loadData('/api/class-options')
    classes.value = await loadData('/api/classes')
    selected.value = classes.value[0]?.id
  } catch {
    error.value = 'Klassen konnten nicht geladen werden.'
  }
})
type Row = {
  id: number
  firstName: string
  lastName: string
  birthDate: string
  excusedMinutes: number
  unexcusedMinutes: number
}
function label(r: Row) {
  return r.lastName + ', ' + r.firstName
}
</script>
<template>
  <h1 v-if="!selected">Schüler</h1>
  <p v-if="error" role="alert">{{ error }}</p>

  <p v-if="options && !classes.length && !error">Keine Klasse verfügbar.</p>
  <MasterDataList
    v-if="selected"
    :key="selected"
    :url="'/api/classes/' + selected + '/students'"
    item-url="/api/students"
    :editable="auth.isAdmin || own"
    title="Schüler"
    column-title="Name"
    :columns="[
      { field: 'unexcusedMinutes', title: 'Unentschuldigt (min)' },
      { field: 'excusedMinutes', title: 'Entschuldigt (min)' }
    ]"
    :label="label"
  >
    <template #filter>
      <label class="class-filter">
        Klasse
        <select v-model="selected">
          <option v-for="c in classes" :key="c.id" :value="c.id">
            {{ c.classCode }}
          </option>
        </select>
      </label>
    </template>
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
      <label>
        Geburtsdatum
        <InputText
          name="birthDate"
          type="date"
          :default-value="row?.birthDate"
          required
        />
      </label>
    </template>
  </MasterDataList>
</template>
