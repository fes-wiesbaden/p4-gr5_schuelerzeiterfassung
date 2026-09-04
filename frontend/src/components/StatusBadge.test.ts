import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import StatusBadge from './StatusBadge.vue'

describe('StatusBadge', () => {
  it.each([
    ['anwesend', 'Anwesend'],
    ['abwesend', 'Abwesend'],
    ['entschuldigt', 'Entschuldigt']
  ] as const)('zeigt für Status %s den Text "%s"', (status, expectedText) => {
    const wrapper = mount(StatusBadge, { props: { status } })

    expect(wrapper.text()).toContain(expectedText)
  })

  it('vermittelt den Status zusätzlich über ein Icon, nicht nur über Farbe', () => {
    const wrapper = mount(StatusBadge, { props: { status: 'abwesend' } })

    expect(wrapper.find('i.pi').exists()).toBe(true)
  })
})
