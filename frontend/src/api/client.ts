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

export function getJson(url: string): Promise<Response> {
  return request(url, {})
}
