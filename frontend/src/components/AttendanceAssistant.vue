<script setup lang="ts">
import PageError, { pageErrorTarget } from '@/components/PageError.vue'
import { provide, computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import {
  imageProblem,
  readTimetableImage,
  timetableDraft,
  type ClassTarget,
  type TimetableDraft
} from '@/api/timetableImport'
import TimetableClassQuestion from './TimetableClassQuestion.vue'
import AssistantCommandDraft from './AssistantCommandDraft.vue'
import {
  commands,
  commandFor,
  type CommandHint,
  type CommandResult
} from '@/api/assistantCommands'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Textarea from 'primevue/textarea'

import {
  assistantAvailability,
  emptyAnswerText,
  emptyDraftText,
  sendChat,
  type ChatMessage,
  type ExcusalHint,
  type ExcusalImpact
} from '@/api/assistant'
import { administrationNavItems, mainNavItems } from '@/layouts/navigation'
import { useAuthStore } from '@/stores/auth'
import ExcusalDraft from './ExcusalDraft.vue'
import AssistantFirelight from './AssistantFirelight.vue'
import { confirmAction } from '@/confirm'

type TranscriptItem = ChatMessage & {
  link?: { label: string; to: RouteLocationRaw }
  local?: boolean
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const open = ref(false)
// Komponentenverweis, von dem nur das DOM-Element gebraucht wird
const imageInput = ref<{ $el: HTMLInputElement }>()
const readingImage = ref(false)
// Gelesener Stundenplan, zu dem die Klasse noch zu klären ist
const classQuestion = ref<TimetableDraft>()
const motionKey = 'klassenlotse-bewegung-pausiert'
// Gesperrter Websitespeicher darf den Chat nicht verhindern
function readMotionPaused() {
  try {
    return localStorage.getItem(motionKey) === 'true'
  } catch {
    return false
  }
}
const motionPaused = ref(readMotionPaused())
const panel = ref<HTMLDialogElement>()
const demoEnabled = ref<boolean>()
const availabilityError = ref(false)
const bubble = ref<HTMLButtonElement>()
const title = ref<HTMLElement>()
const scrollArea = ref<HTMLElement>()
const messages = ref<TranscriptItem[]>([])
const input = ref('')
const error = ref('')
const dialogErrors = ref<HTMLElement>()
provide(pageErrorTarget, dialogErrors)
const answering = ref(false)
let pendingAnswer: AbortController | undefined
let restorePageScroll: (() => void) | undefined
const draft = ref<ExcusalHint>()
const draftVersion = ref(0)
const command = ref<CommandHint>()
const motionLabel = computed(() =>
  motionPaused.value ? 'Animation fortsetzen' : 'Animation pausieren'
)
const availableCommands = computed(() =>
  Object.entries(commands).filter(
    ([, definition]) => !definition.admin || auth.isAdmin
  )
)
const prompts = [
  { icon: 'pi pi-calendar', text: 'Was steht heute im Stundenplan?' },
  { icon: 'pi pi-users', text: 'Wer fehlt heute?' },
  { icon: 'pi pi-question-circle', text: 'Wie korrigiere ich einen Status?' }
]
// Erkennt Zugangsdaten im Text: ein Stichwort, danach im selben Satz „:“, „=“,
// „auf“, „lautet“, „ist“ oder „wird“ und ein Wert, oder eine Kartenkennung
// aus 8, 14 oder 20 Hexzeichen mit mindestens einer Ziffer
const credentialInMessage = [
  /(?:passwort|kennwort|kartenkennung|kartennummer|rfid[- ]?uid)[^.?!\n]{0,40}?(?::|=|\b(?:auf|lautet|ist|wird)\b)\s*\S/iu,
  /\b(?=[0-9a-f]*\d)(?:[0-9a-f]{20}|[0-9a-f]{14}|[0-9a-f]{8})\b/iu
]
const links = computed(() =>
  auth.isAdmin ? [...mainNavItems, ...administrationNavItems] : mainNavItems
)
const quickLinks = mainNavItems.filter(({ routeName }) =>
  ['live-anwesenheit', 'planung'].includes(routeName)
)

async function loadAvailability() {
  availabilityError.value = false
  try {
    demoEnabled.value = (await assistantAvailability()).demoEnabled
    // Das Eingabefeld erscheint erst jetzt: Fokus nachziehen, solange er noch
    // auf der Überschrift des gerade geöffneten Fensters steht
    if (document.activeElement === title.value) void focusComposer()
  } catch {
    availabilityError.value = true
  }
}

// Sperrt das Scrollen der Seite hinter dem Chat und merkt sich den Zustand
function lockPageScroll() {
  if (restorePageScroll) return
  const page = document.documentElement
  const { overflow, paddingRight } = page.style
  const scrollbar = window.innerWidth - page.clientWidth
  page.style.overflow = 'hidden'
  // Gleicht die wegfallende Bildlaufleiste aus, damit die Seite nicht springt
  if (scrollbar > 0) page.style.paddingRight = `${scrollbar}px`
  restorePageScroll = () => {
    page.style.overflow = overflow
    page.style.paddingRight = paddingRight
    restorePageScroll = undefined
  }
}

function composer() {
  return panel.value?.querySelector<HTMLTextAreaElement>(
    '.assistant__composer textarea'
  )
}

// Setzt den Fokus ins Eingabefeld, sofern kein Formular geöffnet ist. Steht er
// schon dort, passiert nichts, damit Ansagen des Chatverlaufs ungestört bleiben
async function focusComposer() {
  await nextTick()
  if (!open.value || draft.value || command.value) return
  const field = composer()
  if (field && document.activeElement !== field)
    field.focus({ preventScroll: true })
}

async function show() {
  if (open.value) return
  open.value = true
  error.value = ''
  lockPageScroll()
  if (demoEnabled.value === undefined) void loadAvailability()
  await nextTick()
  if (panel.value?.showModal) panel.value.showModal()
  else panel.value?.setAttribute('open', '')
  // Direkt lostippen können; ohne Eingabefeld erhält die Überschrift den Fokus
  const field = composer()
  if (field) field.focus({ preventScroll: true })
  else title.value?.focus()
}

async function close(restoreFocus = true) {
  if (panel.value?.close) panel.value.close()
  else panel.value?.removeAttribute('open')
  open.value = false
  restorePageScroll?.()
  await nextTick()
  if (restoreFocus) bubble.value?.focus()
}

function toggleMotion() {
  motionPaused.value = !motionPaused.value
  try {
    localStorage.setItem(motionKey, String(motionPaused.value))
  } catch {
    // Ohne Websitespeicher gilt die Wahl bis zum Neuladen
  }
}

onBeforeUnmount(() => {
  pendingAnswer?.abort()
  restorePageScroll?.()
})

watch(
  [draft, command],
  async ([nextDraft, nextCommand], [previousDraft, previousCommand]) => {
    // Eine ältere Fehlermeldung gehört nicht zum neu geöffneten Formular
    if (nextDraft || nextCommand) error.value = ''
    if (!open.value) return
    await nextTick()
    if (nextDraft || nextCommand) {
      panel.value
        ?.querySelector<HTMLElement>('.command h3, .excusal h3')
        ?.focus()
    } else if (previousDraft || previousCommand) {
      const target =
        composer() ??
        panel.value?.querySelector<HTMLElement>('.assistant__tools select')
      target?.focus()
    }
  }
)

async function scrollToLatest() {
  await nextTick()
  if (scrollArea.value) {
    scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  }
}

function showDraft(hint: ExcusalHint = {}) {
  draft.value = hint
  draftVersion.value += 1
  void scrollToLatest()
}

async function ask(question: string) {
  const content = question.trim()
  if (
    !demoEnabled.value ||
    !content ||
    content.length > 750 ||
    answering.value ||
    draft.value ||
    command.value
  )
    return
  error.value = ''
  if (credentialInMessage.some((pattern) => pattern.test(content))) {
    error.value =
      'Gib Passwörter oder Kartenkennungen im Formular des jeweiligen Auftrags ein.'
    void focusComposer()
    return
  }
  answering.value = true
  const request = new AbortController()
  pendingAnswer = request
  // Nach einem Klick auf einen Vorschlag verschwindet dessen Schaltfläche,
  // deshalb wandert der Fokus schon vor der Antwort ins Eingabefeld
  void focusComposer()
  const previous = messages.value
    .filter((message) => !message.local && message.content.trim())
    .slice(-4)
    .map(({ role, content }) => ({ role, content }))
  const userMessage: ChatMessage = { role: 'user', content }
  messages.value.push(userMessage)
  input.value = ''
  void scrollToLatest()
  try {
    const page = typeof route?.name === 'string' ? route.name : undefined
    const reply = await sendChat(
      [...previous, userMessage],
      page,
      request.signal
    )
    const link =
      reply.kind === 'navigate'
        ? links.value.find(({ routeName }) => routeName === reply.route)
        : undefined
    const opensCommand =
      reply.kind === 'command' &&
      Boolean(reply.command && commandFor(reply.command.key))
    // Eine leere Modellantwort erscheint nie als leere Sprechblase
    const answer = (reply.answer ?? '').trim().slice(0, 750)
    messages.value.push({
      role: 'assistant',
      content:
        reply.kind === 'navigate' && !link
          ? 'Diese Seite ist in deiner Navigation nicht verfügbar.'
          : answer ||
            (reply.kind === 'excusal' || opensCommand
              ? emptyDraftText
              : emptyAnswerText),
      link: link
        ? { label: link.label, to: { name: link.routeName } }
        : undefined
    })
    if (reply.kind === 'excusal') showDraft(reply)
    if (opensCommand && reply.command) command.value = reply.command
  } catch (failure) {
    messages.value.pop()
    input.value = content
    // Nach „Abbrechen“ bleibt nur der Eingabetext stehen, ohne Fehlermeldung
    if (!request.signal.aborted)
      error.value =
        failure instanceof Error
          ? failure.message
          : 'Die Frage konnte nicht gesendet werden.'
  } finally {
    answering.value = false
    pendingAnswer = undefined
    void scrollToLatest()
    void focusComposer()
  }
}

function cancelAnswer() {
  pendingAnswer?.abort()
}

// Liest einen Stundenplan vom hochgeladenen Bild und öffnet ihn als Entwurf.
// Gespeichert wird erst, wenn der Entwurf dort ausdrücklich bestätigt wird
async function readTimetable(event: Event) {
  const field = event.target as HTMLInputElement
  const file = field.files?.[0]
  field.value = ''
  if (!file || answering.value) return
  error.value = imageProblem(file)
  if (error.value) return
  if (
    timetableDraft.value &&
    !(await confirmAction({
      title: 'Offenen Stundenplan-Entwurf ersetzen?',
      message:
        'Es gibt bereits einen noch nicht angelegten Entwurf. Das neue Bild ersetzt ihn samt deinen Änderungen.',
      confirmLabel: 'Entwurf ersetzen',
      danger: true
    }))
  )
    return
  answering.value = true
  readingImage.value = true
  const request = new AbortController()
  pendingAnswer = request
  messages.value.push({
    role: 'user',
    content: `Stundenplan-Bild hochgeladen: ${file.name}`,
    local: true
  })
  void scrollToLatest()
  try {
    const draft = await readTimetableImage(file, request.signal)
    const count = draft.slots.length
    messages.value.push({
      role: 'assistant',
      content: `Ich habe ${count} ${count === 1 ? 'Unterrichtszeit' : 'Unterrichtszeiten'} erkannt. Bevor ich den Entwurf öffne, noch kurz zur Klasse:`,
      local: true
    })
    classQuestion.value = draft
  } catch (failure) {
    messages.value.pop()
    if (!request.signal.aborted)
      error.value =
        failure instanceof Error
          ? failure.message
          : 'Der Stundenplan konnte nicht gelesen werden.'
  } finally {
    answering.value = false
    readingImage.value = false
    pendingAnswer = undefined
    void scrollToLatest()
  }
}

// Übernimmt die Antworten zur Klasse und öffnet den Entwurf. Gespeichert wird
// erst, wenn der Entwurf dort ausdrücklich bestätigt wird
async function openTimetableDraft(target: ClassTarget) {
  const draft = classQuestion.value
  if (!draft) return
  classQuestion.value = undefined
  const classCode =
    target.mode === 'existing'
      ? draft.classes.find((entry) => entry.id === target.classId)?.classCode
      : target.mode === 'new'
        ? target.classCode
        : undefined
  // Wurde die erkannte Klasse korrigiert, soll auch der Planname sie nennen
  const name =
    classCode && draft.classCode
      ? draft.name.replace(draft.classCode, classCode)
      : draft.name
  timetableDraft.value = { ...draft, name, target }
  messages.value.push({
    role: 'assistant',
    content:
      (target.mode === 'existing'
        ? `Alles klar: Der Plan wird der Klasse ${classCode} zugeordnet.`
        : target.mode === 'new'
          ? `Alles klar: Die Klasse ${classCode} lege ich zusammen mit dem Plan an.`
          : 'Alles klar: Der Plan wird keiner Klasse zugeordnet.') +
      ' Ich habe den Entwurf geöffnet. Prüfe ihn in Ruhe – angelegt wird erst, wenn du ihn dort bestätigst.',
    link: {
      label: 'Stundenplan-Entwurf öffnen',
      to: { name: 'stundenplan-entwurf' }
    },
    local: true
  })
  await close(false)
  await router.push({ name: 'stundenplan-entwurf' })
}

function cancelClassQuestion() {
  classQuestion.value = undefined
  messages.value.push({
    role: 'assistant',
    content: 'Abgebrochen – der gelesene Stundenplan wurde verworfen.',
    local: true
  })
  void scrollToLatest()
  void focusComposer()
}

function chooseCommand(event: Event) {
  const select = event.target as HTMLSelectElement
  if (select.value) command.value = { key: select.value, fields: {} }
  select.value = ''
}

function sendOnEnter(event: KeyboardEvent) {
  if (event.shiftKey || event.isComposing) return
  event.preventDefault()
  void ask(input.value)
}

function onConfirmed(impact: ExcusalImpact) {
  draft.value = undefined
  const pending = impact.days.filter(({ pending }) => pending).length
  const booked = impact.days.length - pending
  const sentence = booked
    ? 'Entschuldigung gebucht.'
    : 'Entschuldigung vorgemerkt.'
  const pendingText =
    pending && booked ? ` Weitere Schultage vorgemerkt: ${pending}.` : ''
  messages.value.push({
    role: 'assistant',
    content: sentence + pendingText,
    local: true
  })
  refreshViews()
  void scrollToLatest()
}

function refreshViews() {
  window.dispatchEvent(new Event('attendance:changed'))
  window.dispatchEvent(new Event('planning:changed'))
  window.dispatchEvent(new Event('management:changed'))
}

function onCommandSaved(result: CommandResult, changed: boolean) {
  command.value = undefined
  messages.value.push({
    role: 'assistant',
    content: result.text,
    link: result.link,
    local: true
  })
  if (changed) refreshViews()
  void scrollToLatest()
}
</script>

<template>
  <div class="assistant">
    <Transition name="assistant-panel">
      <dialog
        v-show="open"
        id="attendance-assistant"
        ref="panel"
        class="assistant__panel"
        aria-labelledby="assistant-title"
        @cancel.prevent="close()"
      >
        <header class="assistant__header">
          <i class="brand-mark pi pi-compass" aria-hidden="true" />
          <div>
            <h2 id="assistant-title" ref="title" tabindex="-1">Klassenlotse</h2>
            <p>Schulassistent</p>
          </div>
          <button
            type="button"
            class="assistant__motion"
            :class="{ 'assistant__motion--paused': motionPaused }"
            :aria-label="motionLabel"
            :data-tooltip="motionLabel"
            @click="toggleMotion"
          >
            <i
              :class="motionPaused ? 'pi pi-play' : 'pi pi-pause'"
              aria-hidden="true"
            />
          </button>
          <Button
            type="button"
            icon="pi pi-times"
            aria-label="Klassenlotse schließen"
            rounded
            text
            class="assistant__close"
            @click="close()"
          />
        </header>

        <div ref="dialogErrors" class="assistant__errors" />
        <PageError
          v-if="availabilityError"
          message="Der Chat konnte nicht geladen werden."
        />
        <PageError :message="error" />

        <div ref="scrollArea" class="assistant__scroll">
          <div
            v-if="!messages.length && !command && !draft"
            class="assistant__shortcuts"
          >
            <template v-if="demoEnabled">
              <div class="assistant__welcome">
                <i class="pi pi-compass" aria-hidden="true" />
                <h3>Wie kann ich dir helfen?</h3>
                <p>
                  Frag nach einer Seite, einem Stundenplan oder bereite eine
                  Änderung vor.
                </p>
                <p>
                  Probier es ruhig aus: Gebucht wird erst, wenn du eine Änderung
                  ausdrücklich bestätigst.
                </p>
              </div>
              <button
                v-for="prompt in prompts"
                :key="prompt.text"
                type="button"
                class="assistant__suggestion"
                @click="ask(prompt.text)"
              >
                <i :class="`pi ${prompt.icon}`" aria-hidden="true" />
                <span>{{ prompt.text }}</span>
                <i class="pi pi-arrow-up-right" aria-hidden="true" />
              </button>
            </template>
            <template v-else>
              <p v-if="demoEnabled === false && !availabilityError">
                Der Chat ist zurzeit deaktiviert.
              </p>
              <p v-else-if="!availabilityError" role="status">
                Klassenlotse wird geladen …
              </p>
              <RouterLink
                v-for="item in quickLinks"
                :key="item.routeName"
                class="assistant__link"
                :to="{ name: item.routeName }"
                @click="close(false)"
              >
                {{ item.label }}
                <i class="pi pi-arrow-right" aria-hidden="true" />
              </RouterLink>
            </template>
          </div>
          <div class="assistant__log" role="log" aria-label="Chatverlauf">
            <div
              v-for="(message, index) in messages"
              :key="index"
              class="assistant__message"
              :class="{ 'assistant__message--user': message.role === 'user' }"
            >
              <span class="assistant__speaker">{{
                message.role === 'user' ? 'Du' : 'Klassenlotse'
              }}</span>
              <p>{{ message.content }}</p>
              <RouterLink
                v-if="message.link"
                class="assistant__link"
                :to="message.link.to"
                @click="close(false)"
              >
                {{ message.link.label }}
                <i class="pi pi-arrow-right" aria-hidden="true" />
              </RouterLink>
            </div>
          </div>
          <div v-if="answering" class="assistant__status">
            <p role="status">
              <i class="pi pi-ellipsis-h" aria-hidden="true" />
              {{
                readingImage
                  ? 'Klassenlotse liest den Stundenplan – das kann bis zu anderthalb Minuten dauern …'
                  : 'Klassenlotse antwortet …'
              }}
            </p>
            <Button
              type="button"
              label="Abbrechen"
              size="small"
              text
              @click="cancelAnswer"
            />
          </div>
          <TimetableClassQuestion
            v-if="classQuestion"
            :draft="classQuestion"
            @done="openTimetableDraft"
            @cancel="cancelClassQuestion"
          />
          <AssistantCommandDraft
            v-else-if="command"
            :hint="command"
            :context-class="route?.query.klasse"
            @saved="onCommandSaved"
            @cancel="command = undefined"
          />
          <ExcusalDraft
            v-else-if="draft"
            :key="draftVersion"
            :hint="draft"
            @confirmed="onConfirmed"
            @cancel="draft = undefined"
          />
        </div>

        <div
          v-if="!draft && !command && !classQuestion"
          class="assistant__tools"
        >
          <label>
            <span class="assistant__sr-only">Auftrag auswählen</span>
            <select :disabled="answering" @change="chooseCommand">
              <option value="" disabled selected>Auftrag auswählen</option>
              <option
                v-for="[key, definition] in availableCommands"
                :key="key"
                :value="key"
              >
                {{ definition.title }}
              </option>
            </select>
          </label>
          <Button
            type="button"
            label="Fehlzeit entschuldigen"
            icon="pi pi-calendar"
            size="small"
            outlined
            :disabled="answering"
            @click="showDraft()"
          />
          <template v-if="demoEnabled && auth.isAdmin">
            <Button
              type="button"
              label="Stundenplan aus Bild"
              icon="pi pi-image"
              size="small"
              outlined
              title="Foto oder Screenshot eines Stundenplans hochladen. Klassenlotse erstellt daraus einen Entwurf, den du prüfst und bestätigst."
              :disabled="answering"
              @click="imageInput?.$el.click()"
            />
            <InputText
              ref="imageInput"
              class="assistant__sr-only"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              aria-label="Bild eines Stundenplans hochladen"
              tabindex="-1"
              @change="readTimetable"
            />
          </template>
        </div>

        <form
          v-if="demoEnabled && !draft && !command && !classQuestion"
          class="assistant__composer"
          @submit.prevent="ask(input)"
        >
          <Textarea
            v-model="input"
            aria-label="Nachricht an Klassenlotse"
            aria-describedby="assistant-compose-help"
            placeholder="Schreib eine Frage oder einen Auftrag …"
            :maxlength="750"
            rows="2"
            :readonly="answering"
            :aria-busy="answering"
            @keydown.enter="sendOnEnter"
          />
          <Button
            type="submit"
            icon="pi pi-send"
            aria-label="Senden"
            :disabled="!input.trim() || answering"
          />
          <small id="assistant-compose-help">
            Enter sendet · Umschalt + Enter für eine neue Zeile
          </small>
        </form>
      </dialog>
    </Transition>
    <button
      v-show="!open"
      id="klassenlotse-oeffnen"
      ref="bubble"
      class="assistant__bubble"
      type="button"
      aria-label="Klassenlotse öffnen"
      title="Klassenlotse"
      aria-haspopup="dialog"
      @click="show"
    >
      <AssistantFirelight v-if="!open" :paused="motionPaused" />
    </button>
  </div>
</template>

<style scoped>
.assistant {
  position: fixed;
  z-index: 10;
  right: max(16px, env(safe-area-inset-right));
  bottom: max(16px, env(safe-area-inset-bottom));
  display: grid;
  justify-items: end;
  gap: 10px;
  pointer-events: none;
}

.assistant__bubble,
.assistant__panel {
  pointer-events: auto;
}

.assistant__bubble {
  display: grid;
  place-items: center;
  position: relative;
  isolation: isolate;
  width: 60px;
  height: 60px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: transparent;
  cursor: pointer;
}

.assistant__bubble:focus-visible {
  outline-offset: 8px;
}

.assistant__bubble:hover :deep(.firelight__glass) {
  border-color: #ffda8b;
  box-shadow:
    0 8px 25px #8e4c416b,
    inset 0 2px 6px #fff3bb;
}

.assistant__bubble:active :deep(.firelight) {
  transform: scale(0.96);
}

.assistant__panel {
  display: grid;
  grid-template-rows: auto auto minmax(0, 1fr) auto auto;
  position: fixed;
  inset: auto max(16px, env(safe-area-inset-right))
    max(16px, env(safe-area-inset-bottom)) auto;
  width: min(440px, calc(100vw - 32px));
  height: min(42rem, calc(100dvh - 32px));
  max-width: none;
  max-height: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line);
  color: var(--ink);
  overflow: hidden;
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: 0 14px 40px #35245233;
}

.assistant__panel::backdrop {
  background: #2113394c;
}

.assistant__panel:not([open]) {
  display: none;
}

.assistant__header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--paper);
}

.assistant__header h2 {
  margin: 0;
  font-size: 1.05rem;
}

.assistant__header h2:focus {
  outline: none;
}

.assistant__header p,
.assistant__status p {
  margin: 0;
  color: var(--muted);
  font-size: 0.8rem;
}

.assistant__close,
.assistant__motion {
  display: grid;
  place-items: center;
  flex: none;
  width: 36px;
  min-height: 36px;
}

.assistant__motion {
  position: relative;
  margin-inline-start: auto;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--accent-ink);
  cursor: pointer;
}

.assistant__motion:hover,
.assistant__motion--paused {
  border-color: var(--control-border);
  background: var(--accent-soft);
}

/* Sichtbare Erklärung des Symbols bei Mauszeiger und Tastaturfokus */
.assistant__motion:is(:hover, :focus-visible)::after {
  content: attr(data-tooltip);
  position: absolute;
  z-index: 1;
  top: calc(100% + 6px);
  right: 0;
  padding: 5px 9px;
  border-radius: 7px;
  background: var(--ink);
  color: var(--surface);
  font-size: 0.78rem;
  font-weight: 500;
  white-space: nowrap;
  pointer-events: none;
}

.assistant__log:empty {
  display: none;
}

.assistant__scroll {
  display: grid;
  gap: 14px;
  align-content: start;
  justify-items: stretch;
  padding: 16px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-color: var(--control-border) transparent;
}

.assistant__shortcuts,
.assistant__log {
  display: grid;
  gap: 10px;
}

.assistant__welcome {
  display: grid;
  gap: 5px;
  margin-block: 12px 8px;
  padding: 0 0 8px;
}

.assistant__welcome > i {
  width: fit-content;
  margin-bottom: 4px;
  padding: 9px;
  border-radius: 10px;
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.assistant__welcome h3 {
  margin: 0;
  font-size: 1.25rem;
}

.assistant__welcome p,
.assistant__message p {
  color: var(--accent-ink);
  font-size: 0.83rem;
}

/* Feste Zeile über dem Eingabefeld: beide Einstiege bleiben immer sichtbar */
.assistant__tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid var(--line);
}

.assistant__tools label {
  flex: 1 1 10rem;
  min-width: 0;
}

.assistant__tools select {
  width: 100%;
  min-height: 40px;
  font-size: 0.86rem;
}

.assistant__tools button {
  flex: none;
  min-height: 40px;
}

.assistant__sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  clip-path: inset(50%);
  overflow: hidden;
}

.assistant__errors:not(:empty) {
  display: grid;
  gap: 8px;
  padding: 0 14px 8px;
}

.assistant__welcome p {
  color: var(--muted);
}

.assistant__suggestion {
  display: grid;
  grid-template-columns: 1rem 1fr auto;
  gap: 10px;
  align-items: center;
  padding: 11px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink);
  text-align: left;
  font: inherit;
  font-size: 0.86rem;
  cursor: pointer;
}

.assistant__suggestion:hover {
  background: var(--accent-soft);
}

.assistant__suggestion i {
  color: var(--accent-ink);
}

.assistant__message {
  width: fit-content;
  max-width: 94%;
  padding: 9px 12px;
  border-radius: 12px 12px 12px 4px;
  background: var(--paper);
  overflow-wrap: anywhere;
}

.assistant__message p {
  white-space: pre-wrap;
}

.assistant__message--user {
  justify-self: end;
  border-radius: 12px 12px 4px 12px;
  background: var(--accent-soft);
}

.assistant__speaker {
  display: block;
  margin-bottom: 3px;
  color: var(--accent-ink);
  font-size: 0.72rem;
  font-weight: 650;
}

.assistant__status,
.assistant__status p {
  display: flex;
  gap: 7px;
  align-items: center;
}

.assistant__status {
  justify-content: space-between;
}

.assistant__link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  padding-block: 6px;
  color: var(--accent-ink);
  font-size: 0.875rem;
  font-weight: 650;
  text-underline-offset: 3px;
}

.assistant__link i {
  font-size: 0.75rem;
}

.assistant__composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 44px;
  align-items: end;
  gap: 8px;
  border-top: 1px solid var(--line);
  padding: 12px 14px;
}

.assistant__composer textarea[readonly] {
  background: var(--paper);
}

.assistant__composer textarea {
  width: 100%;
  min-height: 44px;
  max-height: 7rem;
  resize: vertical;
  font: inherit;
  font-size: 0.9rem;
}

.assistant__composer small {
  grid-column: 1 / -1;
  color: var(--muted);
  font-size: 0.72rem;
}

.assistant__composer button {
  min-height: 44px;
}

.assistant-panel-enter-active {
  transition: transform 180ms ease-out;
}

.assistant-panel-enter-from {
  transform: translateY(8px);
}

@media (prefers-reduced-motion: reduce) {
  .assistant-panel-enter-active {
    transition: none;
  }
}
</style>
