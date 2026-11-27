import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { expect, it, vi } from 'vitest'

import { confirmAction } from '@/confirm'
import ConfirmDialog from './ConfirmDialog.vue'

it('fragt über den eigenen Dialog nach und meldet die Entscheidung', async () => {
  // jsdom kennt showModal und close des dialog-Elements nicht
  HTMLDialogElement.prototype.showModal = vi.fn(function (
    this: HTMLDialogElement
  ) {
    this.open = true
  })
  HTMLDialogElement.prototype.close = vi.fn(function (this: HTMLDialogElement) {
    this.open = false
  })
  const browserConfirm = vi.spyOn(window, 'confirm')
  const wrapper = mount(ConfirmDialog, { attachTo: document.body })

  const declined = confirmAction({ title: 'Eintrag löschen?', danger: true })
  await nextTick()
  expect(wrapper.get('h2').text()).toBe('Eintrag löschen?')
  await wrapper.get('button').trigger('click')
  expect(await declined).toBe(false)

  const accepted = confirmAction({
    title: 'Eintrag löschen?',
    confirmLabel: 'Löschen'
  })
  await nextTick()
  const confirm = wrapper.get('button.confirm__ok')
  expect(confirm.text()).toBe('Löschen')
  await confirm.trigger('click')
  expect(await accepted).toBe(true)
  expect(browserConfirm).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('nutzt ohne eingebundenen Dialog die Rückfrage des Browsers', async () => {
  const browserConfirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  expect(await confirmAction({ title: 'Wirklich?' })).toBe(true)
  expect(browserConfirm).toHaveBeenCalledWith('Wirklich?')
})
