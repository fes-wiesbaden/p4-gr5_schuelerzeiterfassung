import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TerminalView from './TerminalView.vue'

describe('TerminalView', () => {
  it('starts ready and names its terminal', () => {
    const terminal = mount(TerminalView, { props: { terminalId: '3' } })

    expect(terminal.get('.terminal__headline').text()).toBe('Karte auflegen')
    expect(terminal.text()).toContain('Terminal 3')
  })
})
