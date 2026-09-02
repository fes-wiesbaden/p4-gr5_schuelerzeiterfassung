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
