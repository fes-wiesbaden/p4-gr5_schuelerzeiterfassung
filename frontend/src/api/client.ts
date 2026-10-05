// Spring Security legt das CSRF-Token in einem Cookie ab. Schreibende Anfragen
// muessen es als Header zurueckschicken, sonst lehnt das Backend sie ab.
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
  const response = await fetch(url, { ...options, credentials: 'include' })
  if (response.status === 401 && url !== '/api/login') {
    await handleUnauthorized()
  }
  return response
}

// credentials: 'include' sorgt dafuer, dass das Sitzungscookie mitgeht.
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
