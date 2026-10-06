import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AppDataTable, { type DataTableColumn } from './AppDataTable.vue'

interface Row {
  id: string
  name: string
}

const columns: DataTableColumn<Row>[] = [{ field: 'name', header: 'Name' }]
const rows: Row[] = [
  { id: '1', name: 'Beispiel, Erika' },
  { id: '2', name: 'Mustermann, Tom' }
]

describe('AppDataTable', () => {
  it('rendert Spaltenüberschriften und Zeilen', () => {
    const wrapper = mount(AppDataTable, {
      props: { columns, rows, rowKey: 'id', caption: 'Testtabelle' }
    })

    expect(wrapper.find('th').text()).toBe('Name')
    expect(wrapper.text()).toContain('Beispiel, Erika')
    expect(wrapper.text()).toContain('Mustermann, Tom')
    expect(wrapper.findAll('tbody tr')).toHaveLength(2)
  })

  it('erlaubt benutzerdefinierten Zellinhalt über benannte Slots', () => {
    const wrapper = mount(AppDataTable, {
      props: { columns, rows, rowKey: 'id', caption: 'Testtabelle' },
      slots: {
        'cell-name':
          '<template #default="{ value }">Custom: {{ value }}</template>'
      }
    })

    expect(wrapper.text()).toContain('Custom: Beispiel, Erika')
  })
})
