import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { expect, it, vi } from 'vitest'
import { ref } from 'vue'
import MasterDataList from './MasterDataList.vue'
import { pageErrorTarget } from './PageError.vue'

type Row = { id: number; name: string }

function pendingResponse() {
  let resolve!: (response: Response) => void
  const response = new Promise<Response>((done) => {
    resolve = done
  })
  return { response, resolve }
}

it('zeigt wiederholte Speicherfehler am Seitenanfang und behält die Eingaben', async () => {
  const scrollTo = vi.fn()
  vi.stubGlobal('scrollTo', scrollTo)
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(Response.json([]))
      .mockImplementation(() =>
        Promise.resolve(
          Response.json({ code: 'INVALID_REQUEST' }, { status: 400 })
        )
      )
  )
  const pageErrors = document.createElement('div')
  document.body.append(pageErrors)
  const wrapper = mount(MasterDataList, {
    attachTo: document.body,
    global: {
      plugins: [PrimeVue],
      provide: { [pageErrorTarget]: ref(pageErrors) }
    },
    props: {
      url: '/api/rooms',
      title: 'Räume',
      editable: true,
      canCreate: true,
      label: (row: { id: number }) => (row as Row).name
    },
    slots: { form: '<input name="roomNumber">' }
  })
  try {
    await flushPromises()
    await wrapper.get('button.primary').trigger('click')
    const input = wrapper.get<HTMLInputElement>('input[name="roomNumber"]')
    await input.setValue('R01')
    const form = wrapper.get('form')

    for (let attempt = 1; attempt <= 2; attempt += 1) {
      await form.trigger('submit')
      await flushPromises()
      const alert = pageErrors.querySelector('[role="alert"]')
      expect(alert?.textContent).toBeTruthy()
      expect(alert === document.activeElement).toBe(true)
      expect(scrollTo).toHaveBeenCalledTimes(attempt)
      expect(input.element.value).toBe('R01')
    }
  } finally {
    wrapper.unmount()
    pageErrors.remove()
  }
})

it('behält den Filter beim Klassenwechsel und ignoriert verspätete Antworten', async () => {
  const second = pendingResponse()
  const third = pendingResponse()
  vi.stubGlobal(
    'fetch',
    vi
      .fn()
      .mockResolvedValueOnce(Response.json([{ id: 1, name: 'Schüler A' }]))
      .mockReturnValueOnce(second.response)
      .mockReturnValueOnce(third.response)
  )
  const wrapper = mount(MasterDataList, {
    global: { plugins: [PrimeVue] },
    props: {
      url: '/api/classes/1/students',
      title: 'Schüler',
      editable: false,
      label: (row: { id: number }) => (row as Row).name
    },
    slots: { filter: '<select aria-label="Klasse"></select>' }
  })
  await flushPromises()
  const filter = wrapper.get('select').element
  const results = wrapper.get('.list-results-content')

  await wrapper.setProps({ url: '/api/classes/2/students' })
  expect(wrapper.get('select').element).toBe(filter)
  expect(results.attributes()).toHaveProperty('inert')
  expect(results.text()).toContain('Schüler A')

  await wrapper.setProps({ url: '/api/classes/3/students' })
  third.resolve(Response.json([{ id: 3, name: 'Schüler C' }]))
  await flushPromises()
  second.resolve(Response.json([{ id: 2, name: 'Schüler B' }]))
  await flushPromises()

  expect(wrapper.get('select').element).toBe(filter)
  expect(results.attributes()).not.toHaveProperty('inert')
  expect(results.text()).toContain('Schüler C')
  expect(results.text()).not.toContain('Schüler B')
  wrapper.unmount()
})
