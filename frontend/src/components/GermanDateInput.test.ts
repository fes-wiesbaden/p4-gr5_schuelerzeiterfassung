import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { expect, it } from 'vitest'

import GermanDateInput from './GermanDateInput.vue'

it('zeigt das Geburtsdatum deutsch an und übergibt dem Formular das ISO-Datum', async () => {
  const form = document.createElement('form')
  const wrapper = mount(GermanDateInput, {
    attachTo: form,
    props: {
      label: 'Geburtsdatum',
      name: 'birthDate',
      modelValue: '2008-04-21',
      'onUpdate:modelValue': (value?: string) =>
        wrapper.setProps({ modelValue: value }),
      required: true
    },
    global: { plugins: [PrimeVue] }
  })

  try {
    const field = wrapper.get<HTMLInputElement>(
      'input[aria-label="Geburtsdatum"]'
    )
    expect(field.element.value).toBe('21.04.2008')
    expect(new FormData(form).get('birthDate')).toBe('2008-04-21')

    await field.setValue('09.05.2007')
    expect(new FormData(form).get('birthDate')).toBe('2007-05-09')

    await field.setValue('31.02.2007')
    await field.trigger('blur')
    expect(field.element.value).toBe('31.02.2007')
    expect(field.element.checkValidity()).toBe(false)
    expect(new FormData(form).get('birthDate')).toBe('')

    // Beim erneuten Fokussieren bleibt der Tippfehler zum Korrigieren stehen
    await field.trigger('focus')
    await flushPromises()
    expect(field.element.value).toBe('31.02.2007')
    expect(field.element.checkValidity()).toBe(false)
  } finally {
    wrapper.unmount()
  }
})
