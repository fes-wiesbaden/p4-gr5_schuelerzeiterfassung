<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')

async function handleSubmit() {
  const erfolgreich = await auth.login(username.value, password.value)
  if (!erfolgreich) {
    password.value = ''
    return
  }

  // Wer vorher auf eine geschuetzte Seite wollte, kommt dorthin zurueck.
  const ziel = route.query.weiter
  if (typeof ziel === 'string') {
    router.replace(ziel)
    return
  }

  router.replace({ name: auth.isAdmin ? 'raeume' : 'live-anwesenheit' })
}
</script>

<template>
  <main class="login">
    <form class="login__card" @submit.prevent="handleSubmit">
      <h1 class="login__title">RFID-Anwesenheitserfassung</h1>
      <p class="login__subtitle">Bitte melde dich an.</p>

      <div class="login__field">
        <label class="login__label" for="login-username">Benutzername</label>
        <InputText
          id="login-username"
          v-model="username"
          autocomplete="username"
          required
          autofocus
          :disabled="auth.busy"
        />
      </div>

      <div class="login__field">
        <label class="login__label" for="login-password">Passwort</label>
        <Password
          v-model="password"
          input-id="login-password"
          autocomplete="current-password"
          :feedback="false"
          toggle-mask
          required
          :disabled="auth.busy"
        />
      </div>

      <p v-if="auth.errorMessage" class="login__error" role="alert">
        {{ auth.errorMessage }}
      </p>

      <Button
        type="submit"
        label="Anmelden"
        class="login__submit"
        :loading="auth.busy"
      />
    </form>
  </main>
</template>

<style scoped>
.login {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background: #f5f6f8;
}

.login__card {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
  max-width: 380px;
  padding: 32px;
  background: #ffffff;
  border: 1px solid #e2e5ea;
  border-radius: 10px;
}

.login__title {
  margin: 0;
  font-size: 1.15rem;
  color: #1e3a5c;
}

.login__subtitle {
  margin: -8px 0 0;
  font-size: 0.9rem;
  color: #6b7688;
}

.login__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.login__label {
  font-size: 0.85rem;
  font-weight: 600;
  color: #2b3648;
}

.login__error {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #f0b4b4;
  border-radius: 6px;
  background: #fdf2f2;
  font-size: 0.85rem;
  color: #a12525;
}

.login__submit {
  margin-top: 4px;
}

.login__field :deep(input) {
  width: 100%;
}
</style>
