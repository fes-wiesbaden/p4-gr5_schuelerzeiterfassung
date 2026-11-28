import { ref } from 'vue'

// Kurze Erfolgsmeldung, die AppLayout am oberen Rand einblendet
export const notice = ref('')
let timer: number | undefined

export function showNotice(text: string) {
  window.clearTimeout(timer)
  notice.value = text
  timer = window.setTimeout(() => (notice.value = ''), 6000)
}

export function clearNotice() {
  window.clearTimeout(timer)
  notice.value = ''
}
