<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MasterDataList from '@/components/MasterDataList.vue'
import StudentFormFields from '@/components/StudentFormFields.vue'
import StudentRfidAssignments from '@/components/StudentRfidAssignments.vue'
import { loadData } from '@/api/client'
import {
  formatAbsenceTotals,
  studentLabel,
  type Student,
  type StudentClass
} from '@/api/students'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ classId?: number }>()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const classes = ref<StudentClass[]>([])
const staffId = ref<number>()
const error = ref('')
const loading = ref(true)
const selection = computed({
  get: () =>
    classes.value.find(
      (entry) =>
        String(entry.id) === (props.classId?.toString() ?? route.query.klasse)
    ),
  set: (entry: StudentClass | undefined) => {
    void router.replace({
      name: 'schueler',
      query: entry ? { klasse: entry.id } : {}
    })
  }
})
const rosterUrl = computed(() =>
  selection.value
    ? `/api/classes/${selection.value.id}/students`
    : '/api/students'
)
const canCreate = computed(() =>
  Boolean(
    selection.value &&
    (auth.isAdmin || selection.value.classTeacherId === staffId.value)
  )
)
const classPage = computed(() => props.classId !== undefined)
const title = computed(() =>
  classPage.value && selection.value ? selection.value.classCode : 'Schüler'
)
const columns = computed(() => [
  ...(!classPage.value
    ? [
        {
          field: 'classId' as const,
          title: 'Klasse',
          format: (row: Student) =>
            classes.value.find((entry) => entry.id === row.classId)
              ?.classCode ?? '—'
        }
      ]
    : []),
  {
    field: 'unexcusedDays' as const,
    title: 'Unentschuldigt',
    format: (row: Student) =>
      formatAbsenceTotals(row.unexcusedDays, row.unexcusedHours)
  },
  {
    field: 'excusedDays' as const,
    title: 'Entschuldigt',
    format: (row: Student) =>
      formatAbsenceTotals(row.excusedDays, row.excusedHours)
  }
])
const profileContext = computed(() =>
  classPage.value
    ? { von: 'klasse' }
    : selection.value
      ? { klasse: selection.value.id }
      : {}
)

onMounted(async () => {
  try {
    const [available, currentStaffId] = await Promise.all([
      loadData<StudentClass[]>('/api/classes'),
      loadData<number>('/api/staff-id')
    ])
    classes.value = available
    staffId.value = currentStaffId
  } catch {
    error.value = 'Klassen konnten nicht geladen werden.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <RouterLink v-if="classPage" class="back" :to="{ name: 'klassen' }">
    Zur Klassenliste
  </RouterLink>
  <h1 v-if="loading || error || (classPage && !selection)">{{ title }}</h1>
  <p v-if="error" role="alert">{{ error }}</p>
  <p v-else-if="loading" role="status">Klassen werden geladen …</p>
  <p v-else-if="classPage && !selection" role="alert">
    Klasse nicht gefunden oder kein Zugriff.
  </p>
  <MasterDataList
    v-else
    :url="rosterUrl"
    :editable="false"
    :can-create="canCreate"
    :title="title"
    column-title="Name"
    :columns="columns"
    :label="studentLabel"
  >
    <template #filter="{ saving }">
      <div v-if="!classPage" class="class-filter">
        <label for="student-list-class">Klasse</label>
        <select id="student-list-class" v-model="selection" :disabled="saving">
          <option :value="undefined">Alle Klassen</option>
          <option v-for="entry in classes" :key="entry.id" :value="entry">
            {{ entry.classCode }}
          </option>
        </select>
      </div>
    </template>
    <template #name="{ row }">
      <RouterLink
        :to="{
          name: 'schueler-detail',
          params: { studentId: row.id },
          query: profileContext
        }"
      >
        {{ studentLabel(row) }}
      </RouterLink>
    </template>
    <template #form>
      <StudentFormFields />
    </template>
  </MasterDataList>
  <StudentRfidAssignments
    v-if="!classPage && !auth.isAdmin && !loading"
    :classes="classes"
  />
</template>

<style scoped>
a {
  color: var(--accent);
}

.back {
  display: inline-block;
  margin-bottom: 1rem;
}
</style>
