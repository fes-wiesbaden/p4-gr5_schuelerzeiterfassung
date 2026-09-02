import { createRouter, createWebHistory } from 'vue-router'

import TerminalView from './views/TerminalView.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/terminal/:terminalId', component: TerminalView, props: true }
  ]
})
