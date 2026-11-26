/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest'

// Quelltexte der Anwendung ohne Tests, die diese Begriffe selbst enthalten
const sources = import.meta.glob<string>(
  ['./**/*.{ts,vue}', '../index.html', '!./**/*.test.ts'],
  { eager: true, query: '?raw', import: 'default' }
)

describe('Aktualisierung ohne Server-Sent Events', () => {
  it('verwendet weder EventSource noch text/event-stream', () => {
    expect(Object.keys(sources)).toEqual(
      expect.arrayContaining([
        './views/LiveAttendanceView.vue',
        '../index.html'
      ])
    )
    for (const [file, source] of Object.entries(sources)) {
      expect(source, file).not.toMatch(/EventSource|text\/event-stream/)
    }
  })
})
