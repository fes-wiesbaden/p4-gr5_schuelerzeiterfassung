<script setup lang="ts">
export type AttendanceStatus = 'anwesend' | 'abwesend' | 'entschuldigt'

const statusConfig: Record<
  AttendanceStatus,
  { label: string; icon: string; modifier: string }
> = {
  anwesend: {
    label: 'Anwesend',
    icon: 'pi-check',
    modifier: 'status-badge--success'
  },
  abwesend: {
    label: 'Abwesend',
    icon: 'pi-times',
    modifier: 'status-badge--danger'
  },
  entschuldigt: {
    label: 'Entschuldigt',
    icon: 'pi-info-circle',
    modifier: 'status-badge--info'
  }
}

const props = defineProps<{ status: AttendanceStatus }>()
</script>

<template>
  <span class="status-badge" :class="statusConfig[props.status].modifier">
    <i class="pi" :class="statusConfig[props.status].icon" aria-hidden="true" />
    {{ statusConfig[props.status].label }}
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.85rem;
  font-weight: 600;
  border: 1px solid transparent;
}

.status-badge--success {
  color: #1a7a3d;
  background: #e7f6ec;
  border-color: #bfe8cc;
}

.status-badge--danger {
  color: #c62828;
  background: #fdecea;
  border-color: #f5c6c3;
}

.status-badge--info {
  color: #6c3fc5;
  background: #f1eafb;
  border-color: #dcc9f5;
}
</style>
