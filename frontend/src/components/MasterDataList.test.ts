import { flushPromises, mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { afterEach, expect, it, vi } from 'vitest'
import MasterDataList from './MasterDataList.vue'

type Row = { id: number; name: string }

function pendingResponse() {
  let resolve!: (response: Response) => void
  const response = new Promise<Response>((done) => {
    resolve = done
  })
  return { response, resolve }
}

afterEach(() => vi.unstubAllGlobals())

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
      label: (row: Row) => row.name
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
