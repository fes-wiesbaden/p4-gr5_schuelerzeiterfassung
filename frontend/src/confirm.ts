import { shallowRef } from 'vue'

export interface ConfirmRequest {
  title: string
  message?: string
  confirmLabel?: string
  danger?: boolean
}

type Pending = ConfirmRequest & { resolve: (confirmed: boolean) => void }

// Die aktuell offene Rückfrage. ConfirmDialog.vue zeigt sie an
export const pendingConfirm = shallowRef<Pending>()
let hostMounted = false

export function setConfirmHost(mounted: boolean) {
  hostMounted = mounted
  if (!mounted) settleConfirm(false)
}

export function settleConfirm(confirmed: boolean) {
  const pending = pendingConfirm.value
  pendingConfirm.value = undefined
  pending?.resolve(confirmed)
}

// Fragt vor einer folgenreichen Aktion nach. Ohne eingebundenen Dialog
// (etwa in Komponententests) dient die Rückfrage des Browsers als Ersatz
export function confirmAction(request: ConfirmRequest): Promise<boolean> {
  if (!hostMounted) {
    return Promise.resolve(
      window.confirm(
        request.message ? `${request.title}\n${request.message}` : request.title
      )
    )
  }
  settleConfirm(false)
  return new Promise((resolve) => {
    pendingConfirm.value = { ...request, resolve }
  })
}
