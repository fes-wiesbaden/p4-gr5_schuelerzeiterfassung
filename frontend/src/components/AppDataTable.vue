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
    <table class="app-table">
      <caption class="app-table__caption">
        {{
          caption
        }}
      </caption>
      <thead>
        <tr>
          <th v-for="column in columns" :key="column.field" scope="col">
            {{ column.header }}
          </th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="String(row[rowKey])">
          <td v-for="column in columns" :key="column.field">
            <slot
              :name="`cell-${column.field}`"
              :row="row"
              :value="row[column.field]"
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

.app-table__caption {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.app-table th,
.app-table td {
  text-align: left;
  padding: 12px 16px;
  border-bottom: 1px solid #e2e5ea;
  white-space: nowrap;
}

.app-table th {
  color: #4a5568;
  font-weight: 600;
  font-size: 0.8rem;
  text-transform: uppercase;
  letter-spacing: 0.03em;
}
</style>
