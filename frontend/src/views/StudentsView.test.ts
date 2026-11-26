import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import PrimeVue from 'primevue/config'
import { afterEach, expect, it, vi } from 'vitest'
import { routeLocationKey, routerKey } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import StudentsView from './StudentsView.vue'

afterEach(() => vi.unstubAllGlobals())

it('legt aus der Gesamtübersicht einen Schüler in einer verwaltbaren Klasse an', async () => {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (init?.method === 'POST')
      return Response.json({ id: 4 }, { status: 201 })
    if (url === '/api/classes')
      return Response.json([
        { id: 1, classCode: 'Eigene Klasse', classTeacherId: 11 },
        { id: 2, classCode: 'Unterrichtete Klasse', classTeacherId: 42 }
      ])
    if (url === '/api/staff-id') return Response.json(11)
    return Response.json([])
  })
  vi.stubGlobal('fetch', fetchMock)
  const pinia = createPinia()
  useAuthStore(pinia).user = { name: 'Lehrkraft', role: 'lehrkraft' }
  const wrapper = mount(StudentsView, {
    global: {
      plugins: [pinia, PrimeVue],
      provide: {
        [routeLocationKey as symbol]: { query: {} },
        [routerKey as symbol]: { replace: vi.fn() }
      },
      stubs: ['RouterLink']
    }
  })

  try {
    await flushPromises()
    await wrapper.get('.list-heading button').trigger('click')
    expect(wrapper.get('form select').text()).toContain('Eigene Klasse')
    expect(wrapper.get('form select').text()).not.toContain(
      'Unterrichtete Klasse'
    )
    expect(wrapper.get('button[type="submit"]').attributes()).toHaveProperty(
      'disabled'
    )
    await wrapper.get('form select').setValue('1')
    await wrapper.get('input[name="firstName"]').setValue('Mika')
    await wrapper.get('input[name="lastName"]').setValue('Beispiel')
    const birthday = wrapper.get('input[placeholder="TT.MM.JJJJ"]')
    await birthday.setValue('14.07.2010')
    await birthday.trigger('blur')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    const post = fetchMock.mock.calls.find(
      ([, request]) => request?.method === 'POST'
    )
    expect(post?.[0]).toBe('/api/classes/1/students')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({
      firstName: 'Mika',
      lastName: 'Beispiel',
      birthDate: '2010-07-14'
    })
    expect(fetchMock.mock.lastCall?.[0]).toBe('/api/students')
  } finally {
    wrapper.unmount()
  }
})
