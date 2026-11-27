import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { expect, it } from 'vitest'

import TimetableClassQuestion from './TimetableClassQuestion.vue'

function draft(classCode: string | null) {
  return {
    name: 'Plan',
    slots: [],
    notes: [],
    rooms: [],
    teachers: [],
    classCode,
    classId: null,
    classes: [
      {
        id: 4,
        classCode: '10/11/12BE14',
        blocks: [{ startsOn: '2999-01-04', endsOn: '2999-01-22' }]
      }
    ],
    blockPlans: []
  }
}

function button(wrapper: ReturnType<typeof mount>, label: string) {
  return wrapper.findAll('button').find((entry) => entry.text() === label)!
}

it('fragt bei unbekannter Klasse nach, ob sie mit angelegt werden soll', async () => {
  const wrapper = mount(TimetableClassQuestion, {
    props: { draft: draft('10/11/12BE13') },
    global: { plugins: [PrimeVue] },
    attachTo: document.body
  })

  expect(wrapper.get('h3').text()).toBe(
    'Ich habe die Klasse „10/11/12BE13“ erkannt. Stimmt das?'
  )
  await button(wrapper, 'Ja, stimmt').trigger('click')
  expect(wrapper.emitted('done')).toBeUndefined()
  expect(wrapper.get('h3').text()).toContain('gibt es noch nicht')

  await button(wrapper, 'Ja, Klasse mit anlegen').trigger('click')
  expect(wrapper.get('h3').text()).toContain(
    'Wie lange gilt dieser Stundenplan'
  )
  await button(wrapper, 'Weiter zum Entwurf').trigger('click')
  expect(wrapper.emitted('done')).toEqual([
    [
      {
        mode: 'new',
        classCode: '10/11/12BE13',
        validFrom: expect.stringMatching(/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/),
        validUntil: undefined
      }
    ]
  ])
  wrapper.unmount()
})

it('fragt bei vorhandener Klasse direkt nach dem Zeitraum', async () => {
  const wrapper = mount(TimetableClassQuestion, {
    props: { draft: draft('10/11/12 be14') },
    global: { plugins: [PrimeVue] },
    attachTo: document.body
  })

  expect(wrapper.text()).toContain('gibt es bereits')
  await button(wrapper, 'Ja, stimmt').trigger('click')
  expect(wrapper.emitted('done')).toBeUndefined()
  // Der noch nicht beendete Unterrichtsblock lässt sich als Zeitraum übernehmen
  await button(wrapper, '04.01.2999 – 22.01.2999').trigger('click')
  await button(wrapper, 'Weiter zum Entwurf').trigger('click')
  expect(wrapper.emitted('done')).toEqual([
    [
      {
        mode: 'existing',
        classId: 4,
        validFrom: '2999-01-04',
        validUntil: '2999-01-22'
      }
    ]
  ])
  wrapper.unmount()
})
