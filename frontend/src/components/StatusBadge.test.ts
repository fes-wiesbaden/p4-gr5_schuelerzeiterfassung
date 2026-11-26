import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import StatusBadge from './StatusBadge.vue'

describe('StatusBadge', () => {
  it.each([
    ['ANWESEND', 'Anwesend'],
    ['ABWESEND', 'Abwesend'],
    ['BETRIEB', 'Im Betrieb'],
    ['BETRIEBLICH_ENTSCHULDIGT', 'Betrieblich entschuldigt'],
    ['ENTSCHULDIGT', 'Entschuldigt'],
    ['MIT_ATTEST_ENTSCHULDIGT', 'Mit Attest entschuldigt']
  ] as const)('zeigt für Status %s den Text "%s"', (status, expectedText) => {
    const wrapper = mount(StatusBadge, { props: { status } })

    expect(wrapper.text()).toContain(expectedText)
  })

  it('vermittelt den Status zusätzlich über ein Icon, nicht nur über Farbe', () => {
    const wrapper = mount(StatusBadge, { props: { status: 'ABWESEND' } })

    expect(wrapper.find('i.pi').exists()).toBe(true)
  })
})
