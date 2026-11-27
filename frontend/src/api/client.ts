// Liest das CSRF-Token aus XSRF-TOKEN. Schreibende Anfragen senden es im Header
// X-XSRF-TOKEN zurück, damit Spring Security die Anfrage prüfen kann
function readCsrfToken(): string {
  const cookie = document.cookie
    .split('; ')
    .find((eintrag) => eintrag.startsWith('XSRF-TOKEN='))

  return cookie ? decodeURIComponent(cookie.split('=')[1]) : ''
}

let handleUnauthorized: () => Promise<void> = async () => {}

export function setUnauthorizedHandler(handler: () => Promise<void>) {
  handleUnauthorized = handler
}

async function request(url: string, options: RequestInit): Promise<Response> {
  // Sendet das Sitzungscookie mit. HTTP 401 außerhalb der Anmeldung
  // löst die Behandlung einer abgelaufenen oder fehlenden Sitzung aus
  const response = await fetch(url, { ...options, credentials: 'include' })
  if (response.status === 401 && url !== '/api/login') {
    await handleUnauthorized()
  }
  return response
}

function sendJson(
  url: string,
  method: 'POST' | 'PUT',
  body: unknown,
  signal?: AbortSignal
): Promise<Response> {
  return request(url, {
    method,
    signal,
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': readCsrfToken()
    },
    body: JSON.stringify(body)
  })
}

export function postJson(
  url: string,
  body: unknown,
  signal?: AbortSignal
): Promise<Response> {
  return sendJson(url, 'POST', body, signal)
}

// Sendet Formulardaten mit Dateien. Den Content-Type samt Trennzeichen setzt
// der Browser selbst
export function postForm(
  url: string,
  body: FormData,
  signal?: AbortSignal
): Promise<Response> {
  return request(url, {
    method: 'POST',
    headers: { 'X-XSRF-TOKEN': readCsrfToken() },
    body,
    signal
  })
}

export function putJson(url: string, body: unknown): Promise<Response> {
  return sendJson(url, 'PUT', body)
}

export function deleteResource(url: string): Promise<Response> {
  return request(url, {
    method: 'DELETE',
    headers: { 'X-XSRF-TOKEN': readCsrfToken() }
  })
}

export function getJson(url: string, signal?: AbortSignal): Promise<Response> {
  return request(url, { signal })
}

// Verständliche Hinweise zu den festen Fehlercodes des Backends. NOT_FOUND
// fehlt bewusst: Was nicht gefunden wurde, nennt jede Aufrufstelle selbst
export const API_ERRORS: Record<string, string> = {
  INVALID_REQUEST: 'Die Eingaben sind unvollständig oder ungültig.',
  ADMINISTRATOR_REQUIRED: 'Diese Aktion ist der Verwaltung vorbehalten.',
  CLASS_MANAGEMENT_DENIED: 'Das darf nur die Klassenleitung dieser Klasse.',
  CLASS_TEACHER_ASSIGNMENT_DENIED:
    'Die Klassenleitung einer anderen Lehrkraft kann nur die Verwaltung festlegen.',
  // Stammdaten
  CLASS_CODE_ALREADY_EXISTS:
    'Dieses Klassenkürzel ist bereits vergeben. Wähle ein anderes.',
  CLASS_HAS_STUDENTS:
    'Die Klasse hat noch Schüler und lässt sich deshalb nicht löschen.',
  STUDENT_HAS_HISTORY:
    'Für diesen Schüler gibt es bereits Anwesenheitsdaten. Er lässt sich deshalb nicht löschen.',
  BIRTH_DATE_IN_FUTURE: 'Das Geburtsdatum darf nicht in der Zukunft liegen.',
  INVALID_STUDENT:
    'Vorname, Nachname und ein gültiges Geburtsdatum sind erforderlich.',
  INVALID_CLASS: 'Das Klassenkürzel fehlt oder ist zu lang.',
  INVALID_TEACHER_PASSWORD:
    'Das Passwort erfüllt die Anforderungen nicht. Wähle ein längeres Passwort.',
  RFID_UID_ALREADY_ASSIGNED:
    'Diese Kartenkennung ist bereits einer anderen Person zugeordnet.',
  INVALID_RFID_UID:
    'Die Kartenkennung muss aus 8, 14 oder 20 Hexzeichen bestehen.',
  MASTER_DATA_CONFLICT:
    'Der Eintrag ist bereits vorhanden oder wird noch verwendet.',
  // Anwesenheit und Fehlzeiten
  ATTENDANCE_DAY_LOCKED_OR_UNPLANNED:
    'Für diesen Tag ist keine Änderung möglich: kein Unterricht geplant oder Tag bereits abgeschlossen.',
  ATTENDANCE_ALREADY_PRESENT:
    'Für diesen Tag wurde bereits Anwesenheit per Karte erfasst. „Abwesend“ ist dann nicht möglich – trage stattdessen Fehlminuten im Schülerprofil ein.',
  ATTENDANCE_BOOKING_CHANGED:
    'Diese Buchung wurde bereits mit anderen Minuten gespeichert. Prüfe den Tag im Schülerprofil.',
  INVALID_ATTENDANCE_DATE:
    'Für dieses Datum ist die Änderung nicht möglich. Prüfe das Datum.',
  INVALID_ATTENDANCE_STATUS: 'Dieser Status ist hier nicht möglich.',
  INVALID_STATUS_TIME:
    'Die Uhrzeit passt nicht: Korrekturen gelten nur am gewählten Tag bis jetzt, künftige Zeiträume werden vorgemerkt – beides zusammen geht nicht in einem Schritt.',
  OVERLAPPING_PLANNED_ATTENDANCE_STATUS:
    'Für einen Teil dieses Zeitraums ist bereits ein Status vorgemerkt.',
  INVALID_MANUAL_ABSENCE_MINUTES: 'Gib eine ganze Zahl ab 1 ein.',
  MANUAL_ABSENCE_EXCEEDS_SCHOOL_DAY:
    'Das sind mehr Fehlminuten, als an diesem Tag noch offen sein können: geplanter Unterricht abzüglich der bereits erfassten Fehlminuten.',
  INVALID_MANUAL_ABSENCE_NOTE:
    'Gib einen Vermerk mit höchstens 200 Zeichen an, warum du die Minuten einträgst.',
  INVALID_EXCUSAL_TIME:
    'Prüfe Datum und Uhrzeit. Für mehrere Tage ist ein Attest erforderlich.',
  EXCUSAL_PREVIEW_CHANGED:
    'Die Fehlzeit hat sich geändert. Berechne die Vorschau erneut.',
  EXCUSAL_PREVIEW_EXPIRED:
    'Diese Vorschau ist abgelaufen. Berechne sie erneut.',
  // Planung
  INVALID_TIMETABLE: 'Gib einen Namen mit höchstens 150 Zeichen an.',
  INVALID_TIMETABLE_SLOT:
    'Der Beginn muss vor dem Ende liegen und ein Raum muss gewählt sein.',
  OVERLAPPING_TIMETABLE_SLOT:
    'Die Unterrichtszeit überschneidet sich mit einer vorhandenen Unterrichtszeit dieses Stundenplans.',
  ROOM_ALREADY_ASSIGNED:
    'Ein Raum des Stundenplans ist in diesem Zeitraum bereits durch eine andere Klasse belegt.',
  TIMETABLE_REUSE_NOT_ALLOWED:
    'Eine andere Klasse nutzt denselben Stundenplan in einem überschneidenden Block.',
  INVALID_BLOCK_PLAN:
    'Bitte Namen angeben; der Beginn darf nicht nach dem Ende liegen.',
  INVALID_BLOCK_ASSIGNMENT:
    'Der Block muss innerhalb des Blockplans liegen; der Beginn darf nicht nach dem Ende liegen.',
  OVERLAPPING_BLOCK_ASSIGNMENT:
    'Der Block überschneidet sich mit einem vorhandenen Block dieses Blockplans.',
  BLOCK_ASSIGNMENT_BELONGS_TO_ANOTHER_PLAN:
    'Der Block gehört nicht zum aktuellen Blockplan der Klasse.',
  BLOCK_ALREADY_ASSIGNED: 'Die Klasse ist diesem Block bereits zugeordnet.',
  CLASS_PLAN_CHANGED:
    'Der Blockplan der Klasse wurde inzwischen geändert. Bitte die Seite neu laden.',
  CURRENT_PLAN_NOT_FINISHED:
    'Der aktuelle Blockplan oder einer seiner Blöcke ist noch nicht beendet.',
  NEXT_PLAN_OVERLAPS_CURRENT_PLAN:
    'Der Folgeplan muss nach dem Ende des aktuellen Blockplans beginnen.',
  NEXT_PLAN_FINISHED: 'Der gewählte Folgeplan ist bereits beendet.',
  CLASS_HAS_NO_STUDENTS: 'Die Klasse hat keine Schüler.',
  CLASS_HAS_REMAINING_SLOTS:
    'Heute stehen für die Klasse noch Unterrichtszeiten an.',
  CLASS_NOT_FULLY_ACCOUNTED:
    'Es sind noch nicht alle Tage der Klasse abgerechnet.',
  CONCURRENT_PLANNING_CHANGE:
    'Die Planung wurde gleichzeitig geändert. Bitte die Seite neu laden und erneut versuchen.',
  INVALID_LESSON_CANCELLATION:
    'An diesem Tag gibt es zu dieser Uhrzeit keinen Unterricht, der ausfallen könnte.',
  INVALID_TIMETABLE_VALID_FROM:
    'Der Zeitraum passt nicht: Der Plan kann frühestens ab morgen gelten und das Ende darf nicht vor dem Beginn liegen.',
  // Klassenlotse
  INVALID_ASSISTANT_MESSAGE:
    'Die Nachricht ist leer oder zu lang. Höchstens 750 Zeichen.',
  ASSISTANT_BUSY:
    'Klassenlotse ist gerade beschäftigt. Versuch es in einem Moment erneut.',
  ASSISTANT_UNAVAILABLE: 'Klassenlotse ist gerade nicht erreichbar.',
  INVALID_TIMETABLE_IMAGE:
    'Das ist kein unterstütztes Bild. Lade ein Foto oder einen Screenshot als PNG, JPEG oder WebP hoch.',
  TIMETABLE_IMAGE_TOO_LARGE:
    'Das Bild ist zu groß. Erlaubt sind höchstens 8 MB.',
  TIMETABLE_NOT_RECOGNIZED:
    'Auf dem Bild war kein Stundenplan zu erkennen. Versuch es mit einem schärferen, geraden Foto des ganzen Plans.'
}

// Liest den Fehlercode einer abgelehnten Antwort. Ohne lesbaren Inhalt bleibt er leer
export async function readProblem(
  response: Response
): Promise<{ code?: string; slotIndex?: number | null }> {
  return response.json().catch(() => ({}))
}

// Hinweistext zu einem Fehlercode; `overrides` erlaubt einen Wortlaut je Aufrufstelle
export function apiErrorMessage(
  code: string | undefined,
  fallback: string,
  overrides: Record<string, string> = {}
): string {
  return (code && (overrides[code] ?? API_ERRORS[code])) || fallback
}

// Schreibt Daten und liefert die Antwort des Backends, etwa `{ changed }`.
// Abgelehnte Änderungen werfen einen Fehler mit verständlichem Hinweis
export async function changeData(
  url: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body?: unknown
): Promise<{ changed?: boolean } | null> {
  const response = await request(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': readCsrfToken()
    },
    body: JSON.stringify(body)
  })
  if (response.ok)
    return response.status === 204 ? null : response.json().catch(() => null)
  const problem = await readProblem(response)
  const known = problem.code && API_ERRORS[problem.code]
  if (known) throw new Error(known)
  if (response.status === 409) {
    throw new Error(
      method === 'DELETE'
        ? 'Löschen nicht möglich: Der Eintrag wird noch verwendet, etwa in der Anwesenheitshistorie.'
        : 'Speichern nicht möglich: Ein Eintrag mit diesen Angaben ist bereits vorhanden.'
    )
  }
  if (response.status === 403)
    throw new Error('Dafür fehlt dir die Berechtigung.')
  if (response.status === 404)
    throw new Error('Der Eintrag wurde nicht gefunden. Lade die Liste neu.')
  if (response.status >= 500)
    throw new Error(
      'Der Server hat einen Fehler gemeldet. Versuch es in einem Moment erneut.'
    )
  throw new Error(
    'Die Eingaben sind unvollständig oder ungültig. Prüfe die Felder.'
  )
}

export async function loadData<T>(
  url: string,
  signal?: AbortSignal
): Promise<T> {
  const response = await getJson(url, signal)
  if (!response.ok) throw new Error('Daten konnten nicht geladen werden.')
  return response.json()
}
