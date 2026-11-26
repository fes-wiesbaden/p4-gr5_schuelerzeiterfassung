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
  body: unknown
): Promise<Response> {
  return request(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': readCsrfToken()
    },
    body: JSON.stringify(body)
  })
}

export function postJson(url: string, body: unknown): Promise<Response> {
  return sendJson(url, 'POST', body)
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

export async function changeData(
  url: string,
  method: 'POST' | 'PUT' | 'DELETE',
  body?: unknown
) {
  const response = await request(url, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': readCsrfToken()
    },
    body: JSON.stringify(body)
  })
  if (response.status === 409) {
    if (method === 'DELETE') {
      throw new Error(
        'Löschen nicht möglich (409). Der Eintrag wird noch verwendet, etwa in der Anwesenheitshistorie.'
      )
    }
    throw new Error(
      'Speichern nicht möglich (409). Bitte prüfe, ob der Eintrag bereits vorhanden ist.'
    )
  }
  if (!response.ok)
    throw new Error(
      'Änderung fehlgeschlagen. Bitte Eingaben und Berechtigung prüfen.'
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
