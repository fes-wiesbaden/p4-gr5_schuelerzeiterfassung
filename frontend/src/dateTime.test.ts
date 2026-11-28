import { expect, it } from 'vitest'

import { formatCalendarWeek } from './dateTime'

it('berechnet die Kalenderwoche auch beim Jahreswechsel nach ISO', () => {
  expect(formatCalendarWeek('2027-01-01')).toBe('53. KW 2026')
  expect(formatCalendarWeek('2027-01-04')).toBe('1. KW 2027')
})
