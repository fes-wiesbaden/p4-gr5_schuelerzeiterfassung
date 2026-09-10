// Spring Security legt das CSRF-Token in einem Cookie ab. Schreibende Anfragen
// muessen es als Header zurueckschicken, sonst lehnt das Backend sie ab.
function readCsrfToken(): string {
  const cookie = document.cookie
    .split('; ')
    .find((eintrag) => eintrag.startsWith('XSRF-TOKEN='))

  return cookie ? decodeURIComponent(cookie.split('=')[1]) : ''
}

// credentials: 'include' sorgt dafuer, dass das Sitzungscookie mitgeht.
export function postJson(url: string, body: unknown): Promise<Response> {
  return fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'X-XSRF-TOKEN': readCsrfToken()
    },
    body: JSON.stringify(body)
  })
}

export function getJson(url: string): Promise<Response> {
  return fetch(url, { credentials: 'include' })
}
