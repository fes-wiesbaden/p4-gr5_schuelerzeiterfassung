<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import GermanDateInput from './GermanDateInput.vue'
import GermanTimeInput from './GermanTimeInput.vue'
import {
  commandFields,
  commandFor,
  fillCommand,
  loadCommandContext,
  selectedSlot,
  type CommandContext,
  type CommandHint,
  type CommandResult,
  type Field,
  type Values
} from '@/api/assistantCommands'
import { formatDate, timeWithSecondsPattern, toCalendarDate } from '@/dateTime'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ hint: CommandHint; contextClass?: unknown }>()
const emit = defineEmits<{
  saved: [result: CommandResult, changed: boolean]
  cancel: []
}>()
const auth = useAuthStore()
const definition = commandFor(props.hint.key)
const fields = computed(() =>
  definition ? commandFields(props.hint.key, definition, auth.isAdmin) : []
)
const confirmLabel = computed(() => {
  if (props.hint.key.startsWith('delete_')) return 'Löschen bestätigen'
  if (props.hint.key === 'remove_rfid') return 'Entfernen bestätigen'
  if (props.hint.key === 'reset_accounts') return 'Zurücksetzen bestätigen'
  if (props.hint.key === 'change_block_plan') return 'Planwechsel bestätigen'
  return 'Änderung bestätigen'
})
const context = ref<CommandContext>()
const values = ref<Values>({})
const prepared = ref<Values>()
const manualRequests = new Map<string, string>()
const previewElement = ref<HTMLElement>()
const error = ref('')
const busy = ref(false)
const request = new AbortController()
const timePattern = new RegExp(`^${timeWithSecondsPattern}$`)

function options(field: Field, inputs = values.value) {
  return context.value ? (field.options?.(context.value, inputs) ?? []) : []
}

function valid(field: Field) {
  const value = values.value[field.key] ?? ''
  if (!value.trim()) return Boolean(field.optional)
  switch (field.kind) {
    case 'date':
      return Boolean(toCalendarDate(value))
    case 'time':
      return timePattern.test(value)
    case 'number':
      return (
        /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 2147483647
      )
    case 'choice':
      return options(field).some((item) => item.value === value)
    default:
      return value.length <= (field.length ?? 100)
  }
}
const complete = computed(() => {
  if (!context.value || !fields.value.every(valid)) return false
  if (
    values.value.startsAt &&
    values.value.endsAt &&
    values.value.startsAt.padEnd(8, ':00') >=
      values.value.endsAt.padEnd(8, ':00')
  )
    return false
  if (
    values.value.endDate &&
    values.value.date &&
    values.value.endDate < values.value.date
  )
    return false
  return true
})

function setDefaults(override = false) {
  const ctx = context.value
  if (!ctx) return
  let existing: Values = {}
  const input = values.value
  switch (props.hint.key) {
    case 'update_student': {
      const s = ctx.students.find(
        (item) => item.id === Number(input.studentName)
      )
      existing = s
        ? {
            firstName: s.firstName,
            lastName: s.lastName,
            birthDate: s.birthDate
          }
        : {}
      break
    }
    case 'update_slot': {
      const lesson = selectedSlot(input, ctx)
      existing = lesson
        ? {
            weekday: lesson.weekday,
            startsAt: lesson.startsAt,
            endsAt: lesson.endsAt,
            room: String(lesson.roomId),
            learningField: lesson.learningField ?? '',
            teacher: lesson.teacherId ? String(lesson.teacherId) : ''
          }
        : {}
      break
    }
    case 'update_class': {
      const c = ctx.classes.find((item) => item.id === Number(input.classCode))
      existing = c
        ? { name: c.classCode, teacher: String(c.classTeacherId) }
        : {}
      break
    }
    case 'update_teacher': {
      const t = ctx.teachers.find((item) => item.id === Number(input.teacher))
      existing = t ? { firstName: t.firstName, lastName: t.lastName } : {}
      break
    }
  }
  for (const [key, value] of Object.entries(existing)) {
    if ((override && !props.hint.fields[key]) || !input[key]) input[key] = value
  }
}

function selected(field: Field, event: Event) {
  values.value[field.key] = (event.target as HTMLSelectElement).value
  if (field.key === 'classCode') {
    values.value.studentName = ''
    values.value.block = ''
  }
  if (field.key === 'timetable') values.value.slot = ''
  if (
    ['classCode', 'studentName', 'slot'].includes(field.key) ||
    (field.key === 'teacher' && props.hint.key === 'update_teacher')
  ) {
    setDefaults(true)
  }
}

function display(field: Field, snapshot: Values) {
  const value = snapshot[field.key] ?? ''
  if (!value) return 'Keine Angabe'
  if (field.kind === 'secret') return '••••••'
  if (field.kind === 'date') return formatDate(value)
  if (field.kind === 'time') return value + ' Uhr'
  if (field.kind === 'choice')
    return (
      options(field, snapshot).find((item) => item.value === value)?.label ?? ''
    )
  return value
}

async function load() {
  if (!definition) return
  busy.value = true
  error.value = ''
  try {
    const data = await loadCommandContext(
      definition,
      auth.isAdmin,
      request.signal
    )
    if (request.signal.aborted) return
    context.value = data
    const hint = props.hint.fields ?? {}
    const contextClass = data.classes.find(
      (item) => String(item.id) === props.contextClass
    )
    values.value = fillCommand(
      fields.value,
      !hint.classCode && !hint.studentName && contextClass
        ? { ...hint, classCode: contextClass.classCode }
        : hint,
      data
    )
    setDefaults()
    if (definition.read && complete.value) await perform({ ...values.value })
  } catch (failure) {
    if (!request.signal.aborted)
      error.value =
        failure instanceof Error
          ? failure.message
          : 'Die Auswahl konnte nicht geladen werden.'
  } finally {
    if (!request.signal.aborted) busy.value = false
  }
}

async function prepare() {
  if (!complete.value || !definition || busy.value) return
  const snapshot = { ...values.value }
  if (definition.read) {
    await perform(snapshot)
    return
  }
  if (props.hint.key === 'add_absence_minutes') {
    const key = `${snapshot.studentName}/${snapshot.date}/${Number(snapshot.minutes)}`
    snapshot.requestId = manualRequests.get(key) ?? crypto.randomUUID()
    manualRequests.set(key, snapshot.requestId)
  }
  prepared.value = snapshot
  error.value = ''
  await nextTick()
  previewElement.value?.focus()
  previewElement.value?.scrollIntoView?.({ block: 'end' })
}

async function perform(snapshot: Values) {
  if (!definition || !context.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await definition.run(snapshot, context.value)
    manualRequests.clear()
    emit('saved', result, !definition.read)
  } catch (failure) {
    error.value =
      failure instanceof TypeError
        ? 'Das Ergebnis konnte nicht abgerufen werden. Prüfe den Eintrag vor einem erneuten Versuch.'
        : failure instanceof Error
          ? failure.message
          : 'Die Aktion ist fehlgeschlagen.'
    if (failure instanceof TypeError) prepared.value = undefined
  } finally {
    busy.value = false
  }
}

function revise() {
  prepared.value = undefined
  error.value = ''
}

function confirm() {
  if (!prepared.value || busy.value) return
  void perform(prepared.value)
}

onMounted(load)
onUnmounted(() => request.abort())
</script>

<template>
  <section class="command" aria-label="Auftrag">
    <div class="command__header">
      <i
        :class="definition?.read ? 'pi pi-search' : 'pi pi-pencil'"
        aria-hidden="true"
      />
      <h3 tabindex="-1">{{ definition?.title ?? 'Aktion nicht verfügbar' }}</h3>
    </div>
    <p v-if="busy && !context" role="status">Auswahl wird geladen …</p>
    <p v-if="error" class="command__error" role="alert">{{ error }}</p>

    <div
      v-if="prepared"
      ref="previewElement"
      class="command__review"
      tabindex="-1"
    >
      <div class="command__preview">
        <p><strong>Änderung prüfen</strong></p>
        <dl>
          <div v-for="field in fields" :key="field.key">
            <dt>{{ field.label }}</dt>
            <dd>{{ display(field, prepared) }}</dd>
          </div>
        </dl>
        <p v-if="definition?.note">{{ definition.note }}</p>
      </div>
      <div class="command__actions">
        <Button
          type="button"
          :label="confirmLabel"
          :severity="definition?.danger ? 'danger' : undefined"
          size="small"
          :loading="busy"
          @click="confirm"
        />
        <Button
          type="button"
          label="Bearbeiten"
          size="small"
          text
          :disabled="busy"
          @click="revise"
        />
        <Button
          type="button"
          label="Abbrechen"
          size="small"
          text
          :disabled="busy"
          @click="emit('cancel')"
        />
      </div>
    </div>

    <form v-else-if="context" @submit.prevent="prepare">
      <fieldset :disabled="busy">
        <label v-for="field in fields" :key="field.key">
          {{ field.label }}
          <select
            v-if="field.kind === 'choice'"
            :value="values[field.key]"
            :required="!field.optional"
            @change="selected(field, $event)"
          >
            <option value="" :disabled="!field.optional">
              {{ field.optional ? 'Ohne Zuordnung' : 'Bitte auswählen' }}
            </option>
            <option
              v-for="item in options(field)"
              :key="item.value"
              :value="item.value"
            >
              {{ item.label }}
            </option>
          </select>
          <GermanDateInput
            v-else-if="field.kind === 'date'"
            v-model="values[field.key]!"
            with-dialog
            :label="field.label"
            required
          />
          <GermanTimeInput
            v-else-if="field.kind === 'time'"
            v-model="values[field.key]!"
            :label="field.label"
            seconds
            required
          />
          <InputText
            v-else
            v-model="values[field.key]!"
            :type="
              field.kind === 'secret'
                ? 'password'
                : field.kind === 'number'
                  ? 'number'
                  : 'text'
            "
            :autocomplete="field.kind === 'secret' ? 'new-password' : 'off'"
            :maxlength="field.length"
            min="1"
            max="2147483647"
            :required="!field.optional"
          />
        </label>
      </fieldset>
      <p v-if="fields.some((field) => field.kind === 'secret')">
        Gib die Kennung hier im Formular ein.
      </p>
      <div class="command__actions">
        <Button
          type="submit"
          :label="definition?.read ? 'Anzeigen' : 'Vorschau anzeigen'"
          size="small"
          :disabled="!complete"
          :loading="busy"
        />
        <Button
          type="button"
          label="Abbrechen"
          size="small"
          text
          :disabled="busy"
          @click="emit('cancel')"
        />
      </div>
    </form>
    <Button
      v-else-if="!busy"
      type="button"
      label="Schließen"
      size="small"
      text
      @click="emit('cancel')"
    />
  </section>
</template>

<style scoped>
.command {
  display: grid;
  min-width: 0;
  gap: 13px;
  padding: 14px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--surface);
}

.command__header,
.command__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.command__header i {
  color: var(--accent-ink);
}

h3,
p,
dl,
dd {
  margin: 0;
}

h3 {
  font-size: 0.98rem;
}
p {
  font-size: 0.84rem;
  color: var(--muted);
}
form {
  display: grid;
  gap: 12px;
}

fieldset {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 9.5rem), 1fr));
  gap: 10px;
  min-width: 0;
  border: 0;
  padding: 0;
  margin: 0;
}

label {
  display: grid;
  min-width: 0;
  gap: 4px;
  align-content: start;
  font-size: 0.82rem;
  font-weight: 600;
}

label :is(input, select) {
  width: 100%;
  min-width: 0;
  min-height: 42px;
  font-size: 0.88rem;
  font-weight: 400;
}

.command__review {
  display: grid;
  gap: 12px;
  scroll-margin-block-end: 14px;
}

.command__preview {
  display: grid;
  gap: 10px;
  padding: 12px;
  border-radius: 8px;
  background: var(--accent-soft);
}

.command__preview dl {
  display: grid;
  gap: 8px;
}

.command__preview dl > div {
  display: grid;
  grid-template-columns: minmax(7rem, 40%) 1fr;
  gap: 8px;
  font-size: 0.85rem;
  overflow-wrap: anywhere;
}

dt {
  color: var(--accent-ink);
}
dd {
  font-weight: 650;
}
.command__error {
  color: #8c3038;
}
</style>
