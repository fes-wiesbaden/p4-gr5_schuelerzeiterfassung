<script setup lang="ts">
import PageError, { pageErrorTarget } from '@/components/PageError.vue'
import { provide, computed, nextTick, ref, watch } from 'vue'
import { useRoute, type RouteLocationRaw } from 'vue-router'
import AssistantCommandDraft from './AssistantCommandDraft.vue'
import {
  commands,
  commandFor,
  type CommandHint,
  type CommandResult
} from '@/api/assistantCommands'
import Button from 'primevue/button'
import Textarea from 'primevue/textarea'

import {
  assistantAvailability,
  sendChat,
  type ChatMessage,
  type ExcusalHint,
  type ExcusalImpact
} from '@/api/assistant'
import { administrationNavItems, mainNavItems } from '@/layouts/navigation'
import { useAuthStore } from '@/stores/auth'
import ExcusalDraft from './ExcusalDraft.vue'
import AssistantFirelight from './AssistantFirelight.vue'

type TranscriptItem = ChatMessage & {
  link?: { label: string; to: RouteLocationRaw }
  local?: boolean
}

const auth = useAuthStore()
const route = useRoute()
const open = ref(false)
const motionKey = 'klassenlotse-bewegung-pausiert'
const motionPaused = ref(localStorage.getItem(motionKey) === 'true')
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
const draft = ref<ExcusalHint>()
const draftVersion = ref(0)
const command = ref<CommandHint>()
const panelLabel = computed(() =>
  open.value ? 'Klassenlotse schließen' : 'Klassenlotse öffnen'
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
const credentialInMessage =
  /(?:passwort|kennwort|kartenkennung|kartennummer|rfid[- ]?uid)\s*(?::|=|lautet\s+|ist\s+)\s*\S/iu
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
  } catch {
    availabilityError.value = true
  }
}

async function show() {
  if (open.value) return
  open.value = true
  if (demoEnabled.value === undefined) void loadAvailability()
  await nextTick()
  if (panel.value?.showModal) panel.value.showModal()
  else panel.value?.setAttribute('open', '')
  title.value?.focus()
}

async function close(restoreFocus = true) {
  if (panel.value?.close) panel.value.close()
  else panel.value?.removeAttribute('open')
  open.value = false
  await nextTick()
  if (restoreFocus) bubble.value?.focus()
}

function toggleMotion() {
  motionPaused.value = !motionPaused.value
  localStorage.setItem(motionKey, String(motionPaused.value))
}

watch(
  [draft, command],
  async ([nextDraft, nextCommand], [previousDraft, previousCommand]) => {
    if (!open.value) return
    await nextTick()
    if (nextDraft || nextCommand) {
      panel.value
        ?.querySelector<HTMLElement>('.command h3, .excusal h3')
        ?.focus()
    } else if (previousDraft || previousCommand) {
      const target = panel.value?.querySelector<HTMLElement>(
        '.assistant__composer textarea, .assistant__actions summary'
      )
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
  if (credentialInMessage.test(content)) {
    error.value =
      'Gib Passwörter oder Kartenkennungen im Formular des jeweiligen Auftrags ein.'
    return
  }
  answering.value = true
  const previous = messages.value
    .filter((message) => !message.local)
    .slice(-4)
    .map(({ role, content }) => ({ role, content }))
  const userMessage: ChatMessage = { role: 'user', content }
  messages.value.push(userMessage)
  input.value = ''
  void scrollToLatest()
  try {
    const page = typeof route?.name === 'string' ? route.name : undefined
    const reply = await sendChat([...previous, userMessage], page)
    const link =
      reply.kind === 'navigate'
        ? links.value.find(({ routeName }) => routeName === reply.route)
        : undefined
    messages.value.push({
      role: 'assistant',
      content:
        reply.kind === 'navigate' && !link
          ? 'Diese Seite ist in deiner Navigation nicht verfügbar.'
          : reply.answer.slice(0, 750),
      link: link
        ? { label: link.label, to: { name: link.routeName } }
        : undefined
    })
    if (reply.kind === 'excusal') showDraft(reply)
    if (
      reply.kind === 'command' &&
      reply.command &&
      commandFor(reply.command.key)
    ) {
      command.value = reply.command
    }
  } catch (failure) {
    messages.value.pop()
    input.value = content
    error.value =
      failure instanceof Error
        ? failure.message
        : 'Die Frage konnte nicht gesendet werden.'
  } finally {
    answering.value = false
    void scrollToLatest()
  }
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
  messages.value.push({ role: 'assistant', content: sentence + pendingText })
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
            :aria-pressed="motionPaused"
            title="Feenlicht-Animation pausieren"
            @click="toggleMotion"
          >
            <i
              :class="motionPaused ? 'pi pi-play' : 'pi pi-pause'"
              aria-hidden="true"
            />
            <span class="assistant__sr-only">Animation pausieren</span>
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
          <p v-if="answering" class="assistant__status" role="status">
            <i class="pi pi-ellipsis-h" aria-hidden="true" /> Klassenlotse
            antwortet …
          </p>
          <details
            v-if="!command && !draft && !answering"
            class="assistant__actions"
          >
            <summary>
              <i class="pi pi-list" aria-hidden="true" /> Auftrag auswählen
            </summary>
            <label>
              <span class="assistant__sr-only">Auftrag</span>
              <select
                @change="
                  command = {
                    key: ($event.target as HTMLSelectElement).value,
                    fields: {}
                  }
                "
              >
                <option value="" disabled selected>Aktion wählen</option>
                <option
                  v-for="[key, definition] in availableCommands"
                  :key="key"
                  :value="key"
                >
                  {{ definition.title }}
                </option>
              </select>
            </label>
          </details>
          <AssistantCommandDraft
            v-if="command"
            :hint="command"
            :context-class="route?.query.klasse"
            @saved="onCommandSaved"
            @cancel="command = undefined"
          />
          <Button
            v-else-if="!draft && !command && !answering"
            type="button"
            label="Fehlzeit entschuldigen"
            icon="pi pi-calendar"
            size="small"
            text
            :disabled="answering"
            @click="showDraft()"
          />
          <ExcusalDraft
            v-else-if="draft"
            :key="draftVersion"
            :hint="draft"
            @confirmed="onConfirmed"
            @cancel="draft = undefined"
          />
        </div>

        <form
          v-if="demoEnabled && !draft && !command"
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
            :disabled="answering"
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
      :aria-label="panelLabel"
      title="Klassenlotse"
      :aria-expanded="open"
      aria-haspopup="dialog"
      @click="show"
      @keydown.enter.prevent="show"
      @keydown.space.prevent="show"
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
  grid-template-rows: auto auto minmax(0, 1fr) auto;
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
.assistant__status {
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
  margin-inline-start: auto;
  border: 1px solid transparent;
  border-radius: 9px;
  background: transparent;
  color: var(--accent-ink);
  cursor: pointer;
}

.assistant__motion:hover,
.assistant__motion[aria-pressed='true'] {
  border-color: var(--control-border);
  background: var(--accent-soft);
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

.assistant__scroll > button {
  justify-self: start;
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
.assistant__message p,
.assistant__actions {
  color: var(--accent-ink);
  font-size: 0.83rem;
}

.assistant__actions summary {
  align-content: center;
  min-height: 32px;
  padding: 6px 0;
  cursor: pointer;
  font-weight: 600;
}

.assistant__actions select {
  width: 100%;
  margin-top: 6px;
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

.assistant__status {
  display: flex;
  gap: 7px;
  align-items: center;
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
