<script setup lang="ts">
import { STATUS_LABELS, type AttendanceStatus } from '@/api/attendance'

const statusConfig: Record<
  AttendanceStatus,
  { icon: string; modifier: string }
> = {
  ANWESEND: { icon: 'pi-check', modifier: 'status-badge--success' },
  ABWESEND: { icon: 'pi-times', modifier: 'status-badge--danger' },
  BETRIEB: { icon: 'pi-building', modifier: 'status-badge--success' },
  BETRIEBLICH_ENTSCHULDIGT: {
    icon: 'pi-info-circle',
    modifier: 'status-badge--info'
  },
  ENTSCHULDIGT: { icon: 'pi-info-circle', modifier: 'status-badge--info' },
  MIT_ATTEST_ENTSCHULDIGT: {
    icon: 'pi-file',
    modifier: 'status-badge--info'
  }
}

const props = defineProps<{ status: AttendanceStatus }>()
</script>

<template>
  <span class="status-badge" :class="statusConfig[props.status].modifier">
    <i class="pi" :class="statusConfig[props.status].icon" aria-hidden="true" />
    {{ STATUS_LABELS[props.status] }}
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
  color: var(--accent-ink);
  background: var(--accent-soft);
}

.status-badge--neutral {
  color: var(--muted);
  background: var(--paper);
}
</style>
