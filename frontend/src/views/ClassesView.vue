<script setup lang="ts">
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
import { ref, onMounted } from 'vue'
import { loadData } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
type Option = { id: number; name: string }
type Options = { staffId: number; blockPlans: Option[]; timetables: Option[] }
const options = ref<Options>()
const error = ref('')
const teachers = ref<{ id: number; firstName: string; lastName: string }[]>([])
onMounted(async () => {
  try {
    if (auth.isAdmin) teachers.value = await loadData('/api/teachers')
    options.value = await loadData('/api/class-options')
  } catch {
    error.value = 'Auswahldaten konnten nicht geladen werden.'
  }
})
type Row = {
  id: number
  classCode: string
  classTeacherId: number
  blockPlanId: number
  timetableId: number
}
function label(r: Row) {
  return r.classCode
}
</script>
<template>
  <p v-if="error" role="alert">{{ error }}</p>
  <MasterDataList
    v-if="options"
    url="/api/classes"
    :editable="auth.isAdmin"
    can-create
    title="Klassen"
    :label="label"
  >
    <template #name="{ row }">
      <RouterLink
        :to="{ name: 'klasse-schueler', params: { classId: row.id } }"
      >
        {{ row.classCode }}
      </RouterLink>
    </template>
    <template #form="{ row }">
      <label>
        Klassenkürzel
        <InputText
          name="classCode"
          type="text"
          :default-value="row?.classCode"
          maxlength="30"
          required
        />
      </label>
      <label v-if="auth.isAdmin">
        Klassenleitung
        <select name="classTeacherId" required>
          <option
            v-for="o in teachers"
            :key="o.id"
            :value="o.id"
            :selected="row?.classTeacherId === o.id"
          >
            {{ o.firstName + ' ' + o.lastName }}
          </option>
        </select>
      </label>
      <label v-if="!row">
        Blockplan
        <select name="blockPlanId" required>
          <option v-for="o in options.blockPlans" :key="o.id" :value="o.id">
            {{ o.name }}
          </option>
        </select>
      </label>
      <label v-if="!row">
        Stundenplan
        <select name="timetableId" required>
          <option v-for="o in options.timetables" :key="o.id" :value="o.id">
            {{ o.name }}
          </option>
        </select>
      </label>
    </template>
  </MasterDataList>
</template>

<style scoped>
a {
  color: var(--accent);
}
</style>
