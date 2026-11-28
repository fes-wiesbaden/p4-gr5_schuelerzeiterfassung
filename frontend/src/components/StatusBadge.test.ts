import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'

import StatusBadge from './StatusBadge.vue'

it('zeigt den Status als Text und nicht nur als Farbe', () => {
  const wrapper = mount(StatusBadge, {
    props: { status: 'MIT_ATTEST_ENTSCHULDIGT' }
  })

  expect(wrapper.text()).toContain('Mit Attest entschuldigt')
})
