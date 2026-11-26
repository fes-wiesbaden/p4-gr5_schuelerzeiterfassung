export const timePattern = '(?:[01][0-9]|2[0-3]):[0-5][0-9]'
export const timeWithSecondsPattern = timePattern + '(?::[0-5][0-9])?'

export function toCalendarDate(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
  const date = new Date(value + 'T12:00:00')
  return Number.isFinite(date.getTime()) && toIsoDate(date) === value
    ? date
    : null
}

export function toIsoDate(date: Date): string {
  const year = String(date.getFullYear()).padStart(4, '0')
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

export function formatDate(iso: string): string {
  const [year, month, day] = iso.split('-')
  return day && month && year ? `${day}.${month}.${year}` : '—'
}

export function parseGermanDate(text: string): string | null {
  const match = text.trim().match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/)
  if (!match) return null
  const [, day, month, year] = match
  const iso = `${year}-${month?.padStart(2, '0')}-${day?.padStart(2, '0')}`
  return toCalendarDate(iso) ? iso : null
}

export function formatCalendarWeek(iso: string): string {
  if (!toCalendarDate(iso)) return ''
  const thursday = new Date(iso + 'T00:00:00Z')
  thursday.setUTCDate(thursday.getUTCDate() + 4 - (thursday.getUTCDay() || 7))
  const year = thursday.getUTCFullYear()
  const first = new Date(thursday)
  first.setUTCMonth(0, 1)
  const week =
    Math.floor((thursday.getTime() - first.getTime()) / (7 * 86_400_000)) + 1
  return `${week}. KW ${year}`
}
