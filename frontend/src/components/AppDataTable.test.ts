import { mount } from '@vue/test-utils'
import { expect, it } from 'vitest'

import AppDataTable, { type DataTableColumn } from './AppDataTable.vue'

it('zeigt Spalten und Zeilen und erlaubt eigenen Zellinhalt über benannte Slots', () => {
  const columns: DataTableColumn<Record<string, unknown>>[] = [
    { field: 'name', header: 'Name' }
  ]
  const wrapper = mount(AppDataTable, {
    props: {
      columns,
      rows: [
        { id: '1', name: 'Beispiel, Erika' },
        { id: '2', name: 'Mustermann, Tom' }
      ],
      rowKey: 'id',
      caption: 'Testtabelle'
    },
    slots: {
      'cell-name':
        '<template #default="{ value }">Custom: {{ value }}</template>'
    }
  })

  expect(wrapper.find('th').text()).toBe('Name')
  expect(wrapper.findAll('tbody tr').map((row) => row.text())).toEqual([
    'Custom: Beispiel, Erika',
    'Custom: Mustermann, Tom'
  ])
})
