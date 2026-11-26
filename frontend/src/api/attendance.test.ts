import { describe, expect, it } from 'vitest'

import { berlinDate, berlinInstant, berlinTime } from './attendance'

describe('Berliner Zeitumrechnung', () => {
  it.each([
    ['2026-09-14', '07:45', '2026-09-14T05:45:00.000Z'],
    ['2026-12-14', '07:45', '2026-12-14T06:45:00.000Z']
  ])('rechnet %s %s in UTC %s um', (date, time, expected) => {
    expect(berlinInstant(date, time)).toBe(expected)
  })

  it('liefert Berliner Tag und Uhrzeit unabhängig vom UTC-Datum', () => {
    const at = new Date('2026-09-13T22:30:00Z')

    expect(berlinDate(at)).toBe('2026-09-14')
    expect(berlinTime(at)).toBe('00:30')
  })
})
