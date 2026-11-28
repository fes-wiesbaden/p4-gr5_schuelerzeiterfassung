import { expect, it } from 'vitest'
import { installGermanValidation } from './formValidation'

it('übersetzt einen leeren Passwortwert und entfernt den Hinweis nach der Eingabe', () => {
  installGermanValidation(document)
  const form = document.createElement('form')
  const password = document.createElement('input')
  password.required = true
  password.type = 'password'
  form.append(password)
  document.body.append(form)
  try {
    expect(form.checkValidity()).toBe(false)
    expect(password.validationMessage).toBe('Bitte gib ein Passwort ein.')
    password.value = 'Beispiel'
    password.dispatchEvent(new Event('input', { bubbles: true }))
    expect(form.checkValidity()).toBe(true)
  } finally {
    form.remove()
  }
})
