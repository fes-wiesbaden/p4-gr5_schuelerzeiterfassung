// Liest das CSRF-Token aus XSRF-TOKEN. POST-Anfragen senden es im Header
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

export function postJson(url: string, body: unknown): Promise<Response> {
  return request(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': readCsrfToken()
    },
    body: JSON.stringify(body)
  })
}

export function getJson(url: string): Promise<Response> {
  return request(url, {})
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

export async function loadData<T>(url: string): Promise<T> {
  const response = await getJson(url)
  if (!response.ok) throw new Error('Daten konnten nicht geladen werden.')
  return response.json()
}
