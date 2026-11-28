<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import MasterDataList from '@/components/MasterDataList.vue'
import { ref, onMounted } from 'vue'
import { loadData } from '@/api/client'
import { personLabel } from '@/api/students'
import { useAuthStore } from '@/stores/auth'
const auth = useAuthStore()
type Option = { id: number; name: string }
type Options = { blockPlans: Option[]; timetables: Option[] }
const options = ref<Options>()
const error = ref('')
const loading = ref(true)
const teachers = ref<{ id: number; firstName: string; lastName: string }[]>([])
async function loadOptions() {
  error.value = ''
  loading.value = true
  try {
    if (auth.isAdmin) teachers.value = await loadData('/api/teachers')
    options.value = await loadData('/api/class-options')
  } catch {
    error.value =
      'Die Auswahldaten für neue Klassen konnten nicht geladen werden. Die Klassenliste bleibt nutzbar.'
  } finally {
    loading.value = false
  }
}
onMounted(loadOptions)
type Row = { id: number; classCode: string; classTeacherId: number }
function label(r: Row) {
  return r.classCode
}
</script>
<template>
  <PageError :message="error">
    <Button
      type="button"
      label="Erneut versuchen"
      outlined
      :disabled="loading"
      @click="loadOptions"
    />
  </PageError>
  <MasterDataList
    url="/api/classes"
    :editable="auth.isAdmin"
    :can-create="Boolean(options)"
    title="Klassen"
    column-title="Klasse"
    entry-name="Klasse"
    search-placeholder="Klasse suchen"
    extra-title="Direkt öffnen"
    :label="label"
  >
    <template #extra="{ row }">
      <RouterLink :to="{ name: 'live-anwesenheit', query: { klasse: row.id } }">
        Anwesenheit heute
      </RouterLink>
      <RouterLink :to="{ name: 'planung', query: { klasse: row.id } }">
        Stundenplan
      </RouterLink>
    </template>
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
            {{ personLabel(o) }}
          </option>
        </select>
      </label>
      <p v-if="!row && options && !options.blockPlans.length" class="form-note">
        Es gibt noch keinen Blockplan. Lege zuerst unter „Stunden- und
        Blockplan“ einen an.
      </p>
      <label v-if="!row && options">
        Blockplan
        <select name="blockPlanId" required>
          <option v-for="o in options.blockPlans" :key="o.id" :value="o.id">
            {{ o.name }}
          </option>
        </select>
      </label>
      <label v-if="!row && options">
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

.form-note {
  margin: 0;
  color: var(--muted);
  font-size: 0.9rem;
}
</style>
