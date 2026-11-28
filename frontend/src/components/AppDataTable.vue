<script setup lang="ts" generic="T extends Record<string, unknown>">
export interface DataTableColumn<T> {
  field: keyof T & string
  header: string
}

defineProps<{
  columns: DataTableColumn<T>[]
  rows: T[]
  rowKey: keyof T & string
  caption: string
}>()
</script>

<template>
  <div class="app-table-scroll">
    <table class="app-table" role="table">
      <caption class="visually-hidden">
        {{
          caption
        }}
      </caption>
      <thead role="rowgroup">
        <tr role="row">
          <th
            v-for="column in columns"
            :key="column.field"
            scope="col"
            role="columnheader"
          >
            {{ column.header }}
          </th>
        </tr>
      </thead>
      <tbody role="rowgroup">
        <tr v-for="row in rows" :key="String(row[rowKey])" role="row">
          <td v-for="column in columns" :key="column.field" role="cell">
            <slot
              :name="`cell-${column.field}`"
              :value="row[column.field]"
              :row="row"
            >
              {{ row[column.field] }}
            </slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.app-table-scroll {
  overflow-x: auto;
}

.app-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9rem;
}

.app-table th,
.app-table td {
  text-align: left;
  padding: 12px 16px;
  border-bottom: 1px solid var(--line);
  white-space: nowrap;
}

.app-table th {
  color: var(--muted);
  font-weight: 600;
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}
</style>
