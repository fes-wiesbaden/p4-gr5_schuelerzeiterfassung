import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import TerminalView from './TerminalView.vue'

// jsdom hat kein EventSource, darum hier ein Ersatz für die Tests.
class FakeEventSource {
  static last: FakeEventSource | undefined

  url: string
  closed = false
  handlers: Record<string, (event: Event) => void> = {}

  constructor(url: string) {
    this.url = url
    FakeEventSource.last = this
  }

  addEventListener(type: string, handler: (event: Event) => void) {
    this.handlers[type] = handler
  }

  close() {
    this.closed = true
  }

  scan(result: string) {
    const data = JSON.stringify({ result })
    this.handlers.scan(new MessageEvent('scan', { data }))
  }
}

async function scan(result: string) {
  FakeEventSource.last?.scan(result)
  await nextTick()
}

async function wait(seconds: number) {
  vi.advanceTimersByTime(seconds * 1000)
  await nextTick()
}

function openTerminal() {
  return mount(TerminalView, { props: { terminalId: '3' } })
}

beforeEach(() => {
  FakeEventSource.last = undefined
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-09-02T07:31:00'))
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('TerminalView', () => {
  it('starts ready and names its terminal', () => {
    const terminal = openTerminal()

    expect(terminal.get('.terminal__headline').text()).toBe('Karte auflegen')
    expect(terminal.text()).toContain('Terminal 3')
  })

  it('subscribes to the event stream of its own terminal', () => {
    openTerminal()

    expect(FakeEventSource.last?.url).toBe('/api/terminals/3/events')
  })

  it('closes the event stream when the view is left', () => {
    openTerminal().unmount()

    expect(FakeEventSource.last?.closed).toBe(true)
  })

  it('shows the current time while ready', () => {
    const terminal = openTerminal()

    expect(terminal.get('.terminal__badge').text()).toBe('Bereit · 07:31 Uhr')
  })

  it('reports the connection state in the footer', async () => {
    const terminal = openTerminal()
    expect(terminal.get('.terminal__connection').text()).toBe(
      '● nicht verbunden'
    )

    FakeEventSource.last?.handlers.open(new Event('open'))
    await nextTick()

    expect(terminal.get('.terminal__connection').text()).toBe('● verbunden')
  })
})

describe('TerminalView scan results', () => {
  it('reports a processed scan as recorded', async () => {
    const terminal = openTerminal()
    await scan('VERARBEITET')

    expect(terminal.get('.terminal__headline').text()).toBe('Erfasst')
    expect(terminal.get('.terminal__stage').classes()).toContain(
      'terminal__stage--success'
    )
  })

  it('reports a rejected scan as not recorded', async () => {
    const terminal = openTerminal()
    await scan('ABGELEHNT')

    expect(terminal.get('.terminal__headline').text()).toBe('Nicht erfasst')
    expect(terminal.get('.terminal__stage').classes()).toContain(
      'terminal__stage--error'
    )
  })

  it('marks every state by symbol and not by colour alone', async () => {
    const terminal = openTerminal()
    expect(terminal.get('.terminal__symbol').text()).toBe('⌾')

    await scan('VERARBEITET')
    expect(terminal.get('.terminal__symbol').text()).toBe('✓')

    await scan('ABGELEHNT')
    expect(terminal.get('.terminal__symbol').text()).toBe('✕')
  })

  it('returns to the ready state after three seconds', async () => {
    const terminal = openTerminal()

    await scan('VERARBEITET')
    await wait(3)

    expect(terminal.get('.terminal__headline').text()).toBe('Karte auflegen')
  })
})
