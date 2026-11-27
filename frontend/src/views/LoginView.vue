<script setup lang="ts">
import PageError from '@/components/PageError.vue'
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'

import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const username = ref('')
const password = ref('')
const passwordVisible = ref(false)

async function handleSubmit() {
  passwordVisible.value = false
  if (!(await auth.login(username.value, password.value))) {
    password.value = ''
    return
  }

  // Das vom Router im Parameter weiter gespeicherte Ziel nach erfolgreicher Anmeldung öffnen
  const ziel = route.query.weiter
  if (typeof ziel === 'string') {
    router.replace(ziel)
    return
  }

  router.replace({ name: 'live-anwesenheit' })
}
</script>

<template>
  <main class="login">
    <div class="login__frame">
      <div class="login__visual" aria-hidden="true">
        <div class="login__card-art login__card-art--back" />
        <div class="login__card-art login__card-art--front">
          <i class="pi pi-wifi" />
        </div>
      </div>
      <div class="login__main">
        <div class="login__brand">
          <i class="brand-mark pi pi-id-card" aria-hidden="true" />
          Anwesenheitserfassung
        </div>
        <form class="login__form" @submit.prevent="handleSubmit">
          <h1>Anmelden</h1>
          <p
            v-if="route.query.grund === 'abgelaufen' && !auth.errorMessage"
            class="login__info"
            role="status"
          >
            Deine Sitzung ist abgelaufen oder du hast dich an einem anderen
            Gerät angemeldet. Melde dich bitte erneut an – danach geht es an
            derselben Stelle weiter.
          </p>
          <PageError :message="auth.errorMessage" />

          <div class="login__field">
            <label for="login-username">Benutzername</label>
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
            <label for="login-password">Passwort</label>
            <div class="login__password">
              <InputText
                id="login-password"
                v-model="password"
                :type="passwordVisible ? 'text' : 'password'"
                autocomplete="current-password"
                required
                :disabled="auth.busy"
              />
              <Button
                type="button"
                class="login__password-toggle"
                :icon="passwordVisible ? 'pi pi-eye-slash' : 'pi pi-eye'"
                text
                rounded
                size="small"
                :aria-label="
                  passwordVisible ? 'Passwort verbergen' : 'Passwort anzeigen'
                "
                aria-controls="login-password"
                :aria-pressed="passwordVisible"
                :disabled="auth.busy"
                @click="passwordVisible = !passwordVisible"
              />
            </div>
          </div>

          <Button
            type="submit"
            label="Anmelden"
            class="login__submit"
            :loading="auth.busy"
          />
          <p class="login__help">
            Zugangsdaten vergessen? Die Verwaltung setzt dein Passwort zurück.
          </p>
        </form>
      </div>
    </div>
  </main>
</template>

<style scoped>
.login {
  display: grid;
  place-items: center;
  min-height: 100svh;
  padding: 24px;
  background:
    radial-gradient(ellipse at 16% 10%, #ede3f9, transparent 60%), var(--paper);
}

.login__frame {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1fr);
  width: min(100%, 880px);
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
}

.login__visual {
  position: relative;
  isolation: isolate;
  overflow: hidden;
  background:
    radial-gradient(ellipse at 44% 90%, #a17ade, transparent 70%), #442970;
}

.login__card-art {
  position: absolute;
  top: 50%;
  left: 50%;
  width: min(60%, 228px);
  aspect-ratio: 1.58;
  border-radius: 14px;
}

.login__card-art--back {
  background: #8360be;
  transform: translate(-54%, -55%) rotate(-23deg);
}

.login__card-art--front {
  display: grid;
  place-items: center;
  background: #e9def7;
  color: #51348e;
  box-shadow: 0 14px 30px #26154154;
  transform: translate(-44%, -40%) rotate(13deg);
}

.login__card-art--front i {
  font-size: clamp(1.5rem, 4vw, 2.25rem);
  transform: rotate(90deg);
}

.login__main {
  display: grid;
  align-content: center;
  gap: 36px;
  min-width: 0;
  padding: clamp(26px, 5vw, 62px);
}

.login__brand {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 0.9rem;
  font-weight: 650;
}

.login__form {
  display: grid;
  gap: 18px;
}

.login__form h1 {
  margin: 0 0 6px;
  font-size: clamp(2rem, 5vw, 3rem);
}

.login__field {
  display: grid;
  gap: 6px;
}

.login__info {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-size: 0.9rem;
  line-height: 1.4;
}

.login__help {
  margin: 0;
  color: var(--muted);
  font-size: 0.85rem;
}

.login__field label {
  font-size: 0.85rem;
  font-weight: 650;
}

.login__submit {
  min-height: 44px;
  margin-top: 4px;
}

.login__field :deep(input) {
  width: 100%;
  min-height: 44px;
  border-color: var(--control-border);
  border-radius: 10px;
}

.login__password {
  position: relative;
}

.login__password :deep(input) {
  padding-inline-end: 44px;
}

.login__password-toggle {
  position: absolute;
  inset-inline-end: 4px;
  top: 50%;
  transform: translateY(-50%);
}

@media (max-width: 650px) {
  .login__frame {
    grid-template-columns: minmax(0, 1fr);
    max-width: 440px;
  }

  .login__visual {
    min-height: 122px;
  }

  .login__card-art {
    width: 114px;
  }

  .login__main {
    gap: 24px;
  }
}

@media (max-width: 360px) {
  .login {
    padding: 14px;
  }
}
</style>
