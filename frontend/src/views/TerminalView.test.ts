import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TerminalView from './TerminalView.vue'

describe('TerminalView', () => {
  it('shows neutral feedback without personal data', () => {
    const wrapper = mount(TerminalView, { props: { terminalId: '1' } })

    expect(wrapper.text()).toContain('Bereit für die Erfassung.')
  })
})
