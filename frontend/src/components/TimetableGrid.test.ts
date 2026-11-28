import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { expect, it } from 'vitest'

import TimetableGrid from './TimetableGrid.vue'

it('markiert ausgefallene Stunden und meldet Klicks auf Stunde und Tag', async () => {
  const regular = {
    startsAt: '07:30:00',
    endsAt: '09:00:00',
    roomNumber: 'A 213',
    subject: 'Deutsch',
    cancelled: false
  }
  const cancelled = {
    startsAt: '09:15:00',
    endsAt: '10:45:00',
    roomNumber: 'A 213',
    subject: 'Sport',
    cancelled: true
  }
  const wrapper = mount(TimetableGrid, {
    props: {
      days: [{ date: '2026-11-23', lessons: [regular, cancelled] }],
      caption: 'Wochenstundenplan',
      cancellable: true
    },
    global: { plugins: [PrimeVue] }
  })

  const lessons = wrapper.findAll('button.week-grid__toggle')
  expect(lessons.map((lesson) => lesson.attributes('aria-pressed'))).toEqual([
    'false',
    'true'
  ])
  expect(wrapper.findAll('td.week-grid__cancelled')).toHaveLength(1)
  expect(wrapper.get('td.week-grid__cancelled').text()).toContain('Fällt aus')

  await lessons[0]!.trigger('click')
  expect(wrapper.emitted('toggleLesson')).toEqual([['2026-11-23', regular]])

  const day = wrapper.get('button.week-grid__day-toggle')
  expect(day.text()).toBe('Ganzer Tag fällt aus')
  await day.trigger('click')
  expect(wrapper.emitted('toggleDay')).toEqual([['2026-11-23', true]])
  wrapper.unmount()
})

it('bietet ohne Freigabe keine Ausfall-Schaltflächen an', () => {
  const wrapper = mount(TimetableGrid, {
    props: {
      days: [
        {
          lessons: [
            { startsAt: '07:30:00', endsAt: '09:00:00', roomNumber: 'A 213' }
          ]
        }
      ],
      caption: 'Stundenplan'
    },
    global: { plugins: [PrimeVue] }
  })
  expect(wrapper.find('button.week-grid__toggle').exists()).toBe(false)
  expect(wrapper.find('button.week-grid__day-toggle').exists()).toBe(false)
  wrapper.unmount()
})
