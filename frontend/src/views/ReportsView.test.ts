import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, expect, it, vi } from 'vitest'
import ReportsView from './ReportsView.vue'
import { loadData, postJson } from '@/api/client'

vi.mock('@/api/client', () => ({ loadData: vi.fn(), postJson: vi.fn() }))

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

it('lädt eine Klasse, einen Schüler und eine klassenübergreifende Gruppe herunter', async () => {
  vi.mocked(loadData)
    .mockResolvedValueOnce([
      { id: 1, classCode: '26A' },
      { id: 2, classCode: '26B' }
    ])
    .mockResolvedValueOnce([{ id: 11, firstName: 'Ada', lastName: 'Muster' }])
    .mockResolvedValueOnce([{ id: 21, firstName: 'Max', lastName: 'Muster' }])
  vi.mocked(postJson).mockImplementation(
    async () => new Response('Klasse;Name')
  )
  vi.stubGlobal('URL', {
    createObjectURL: vi.fn(() => 'blob:csv'),
    revokeObjectURL: vi.fn()
  })
  const download = vi
    .spyOn(HTMLAnchorElement.prototype, 'click')
    .mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.download).toBe('anwesenheit.csv')
    })

  const wrapper = mount(ReportsView)
  await flushPromises()
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  await wrapper.get('select').setValue('student')
  await flushPromises()
  await wrapper.findAll('select')[1]!.setValue('11')
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  await wrapper.get('select').setValue('group')
  for (const checkbox of wrapper.findAll('input[type="checkbox"]')) {
    await checkbox.setValue(true)
  }
  await wrapper.get('form').trigger('submit')
  await flushPromises()

  expect(vi.mocked(postJson).mock.calls).toEqual([
    ['/api/attendance/export', { classes: { 1: [] } }],
    ['/api/attendance/export', { classes: { 1: [11] } }],
    ['/api/attendance/export', { classes: { 1: [11], 2: [21] } }]
  ])
  expect(download).toHaveBeenCalledTimes(3)
  wrapper.unmount()
})
