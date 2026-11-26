<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import { changeData, getJson, loadData } from '@/api/client'
import type { Student, StudentClass } from '@/api/students'
import StudentFormFields from '@/components/StudentFormFields.vue'
import StudentRfidCards from '@/components/StudentRfidCards.vue'
import { useAuthStore } from '@/stores/auth'

const StudentAttendanceHistory = defineAsyncComponent(
  () => import('@/components/StudentAttendanceHistory.vue')
)

const props = defineProps<{ studentId: number }>()
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const student = ref<Student>()
const schoolClass = ref<StudentClass>()
const staffId = ref<number>()
const loading = ref(true)
const editing = ref(false)
const busy = ref(false)
const error = ref('')
const actionError = ref('')
const editor = ref<HTMLFieldSetElement>()
const editButton = ref<InstanceType<typeof Button>>()
const canManage = computed(
  () =>
    auth.isAdmin ||
    (staffId.value !== undefined &&
      schoolClass.value?.classTeacherId === staffId.value)
)

const fromClass = computed(
  () => route.query.von === 'klasse' && student.value !== undefined
)
const returnTo = computed(() =>
  fromClass.value && student.value
    ? { name: 'klasse-schueler', params: { classId: student.value.classId } }
    : {
        name: 'schueler',
        query:
          typeof route.query.klasse === 'string'
            ? { klasse: route.query.klasse }
            : {}
      }
)

watch(
  () => props.studentId,
  async (id, _, onCleanup) => {
    let current = true
    onCleanup(() => (current = false))
    student.value = undefined
    schoolClass.value = undefined
    staffId.value = undefined
    editing.value = false
    busy.value = false
    error.value = ''
    actionError.value = ''
    loading.value = true
    if (!Number.isSafeInteger(id) || id < 1) {
      error.value = 'Schüler nicht gefunden.'
      loading.value = false
      return
    }
    try {
      const response = await getJson(`/api/students/${id}`)
      if (!current) return
      if (response.status === 404) {
        error.value = 'Schüler nicht gefunden oder kein Zugriff.'
        return
      }
      if (!response.ok) throw new Error()
      const data: Student = await response.json()
      const [classes, currentStaffId] = await Promise.all([
        loadData<StudentClass[]>('/api/classes'),
        auth.isAdmin ? undefined : loadData<number>('/api/staff-id')
      ])
      if (!current) return
      schoolClass.value = classes.find((entry) => entry.id === data.classId)
      staffId.value = currentStaffId
      student.value = data
    } catch {
      if (current) error.value = 'Schülerdaten konnten nicht geladen werden.'
    } finally {
      if (current) loading.value = false
    }
  },
  { immediate: true }
)

async function edit() {
  editing.value = true
  actionError.value = ''
  await nextTick()
  editor.value?.querySelector('input')?.focus()
}

async function closeEditor() {
  editing.value = false
  await nextTick()
  editButton.value?.$el.focus()
}

async function reloadStudent() {
  const id = props.studentId
  try {
    const data = await loadData<Student>(`/api/students/${id}`)
    if (props.studentId === id && student.value?.id === id) student.value = data
  } catch {
    if (props.studentId === id) {
      actionError.value = 'Die Schülerdaten konnten nicht aktualisiert werden.'
    }
  }
}

async function save(event: Event) {
  const id = props.studentId
  if (!canManage.value || busy.value || student.value?.id !== id) return
  const form = event.currentTarget as HTMLFormElement
  const body = Object.fromEntries(new FormData(form))
  busy.value = true
  actionError.value = ''
  try {
    await changeData(`/api/students/${id}`, 'PUT', body)
    if (props.studentId === id) {
      await reloadStudent()
      await closeEditor()
    }
  } catch (e) {
    if (props.studentId === id) {
      actionError.value =
        e instanceof Error ? e.message : 'Speichern fehlgeschlagen.'
    }
  } finally {
    if (props.studentId === id) busy.value = false
  }
}

async function remove() {
  const data = student.value
  const id = props.studentId
  if (!data || data.id !== id || !canManage.value || busy.value) return
  if (!window.confirm(`${data.firstName} ${data.lastName} wirklich löschen?`))
    return

  busy.value = true
  actionError.value = ''
  try {
    await changeData(`/api/students/${id}`, 'DELETE')
    if (props.studentId === id) {
      await router.replace({
        name: 'schueler',
        query: { klasse: data.classId }
      })
    }
  } catch (e) {
    if (props.studentId === id) {
      actionError.value =
        e instanceof Error ? e.message : 'Löschen fehlgeschlagen.'
    }
  } finally {
    if (props.studentId === id) busy.value = false
  }
}

function birthday(value: string) {
  return new Intl.DateTimeFormat('de-DE', {
    dateStyle: 'medium',
    timeZone: 'UTC'
  }).format(new Date(value))
}
</script>

<template>
  <section class="student-detail">
    <RouterLink class="back" :to="returnTo">
      <i class="pi pi-arrow-left" aria-hidden="true" />
      {{ fromClass ? 'Zur Klasse' : 'Zur Schülerliste' }}
    </RouterLink>
    <p v-if="loading" role="status">Schülerdaten werden geladen …</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-else-if="student">
      <header class="profile-header">
        <div>
          <h1>{{ student.firstName }} {{ student.lastName }}</h1>
          <p v-if="schoolClass" class="class-code">
            {{ schoolClass.classCode }}
          </p>
        </div>
        <div v-if="canManage && !editing" class="actions">
          <Button
            ref="editButton"
            label="Bearbeiten"
            icon="pi pi-pencil"
            severity="secondary"
            :disabled="busy"
            @click="edit"
          />
          <Button
            label="Löschen"
            icon="pi pi-trash"
            severity="danger"
            outlined
            :disabled="busy"
            @click="remove"
          />
        </div>
      </header>
      <p v-if="actionError" role="alert">{{ actionError }}</p>

      <fieldset v-if="editing" ref="editor" :disabled="busy">
        <legend>Stammdaten bearbeiten</legend>
        <form @submit.prevent="save">
          <StudentFormFields :student="student" />
          <div class="actions">
            <Button label="Speichern" type="submit" :loading="busy" />
            <Button
              label="Abbrechen"
              type="button"
              severity="secondary"
              @click="closeEditor"
            />
          </div>
        </form>
      </fieldset>
      <dl v-else class="student-facts">
        <div>
          <dt>Geburtsdatum</dt>
          <dd>{{ birthday(student.birthDate) }}</dd>
        </div>
        <div>
          <dt>Unentschuldigt</dt>
          <dd>{{ student.unexcusedMinutes }} min</dd>
        </div>
        <div>
          <dt>Entschuldigt</dt>
          <dd>{{ student.excusedMinutes }} min</dd>
        </div>
      </dl>

      <StudentAttendanceHistory
        :key="student.id"
        :student-id="student.id"
        @corrected="reloadStudent"
      />
      <StudentRfidCards
        v-if="auth.isAdmin"
        :key="student.id"
        :student-id="student.id"
      />
    </template>
  </section>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  color: var(--accent);
}

.profile-header,
.actions,
.student-facts {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.75rem;
}

.profile-header {
  justify-content: space-between;
  margin-block: 1rem;
}

h1,
.class-code {
  margin: 0;
}

.class-code,
dt {
  color: #445161;
}

.student-facts {
  gap: 1rem 2rem;
  margin-block: 0 1.5rem;
}

dd {
  margin: 0.25rem 0 0;
  font-weight: 600;
}

fieldset {
  width: 100%;
  min-width: 0;
  max-width: 34rem;
  margin: 0 0 1.5rem;
  padding: 1rem;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: white;
}

form,
form :deep(label) {
  display: grid;
  gap: 0.375rem;
}

form {
  gap: 0.75rem;
}
</style>
