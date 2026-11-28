type Control = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement

function isControl(target: EventTarget | null): target is Control {
  return (
    target instanceof HTMLInputElement ||
    target instanceof HTMLSelectElement ||
    target instanceof HTMLTextAreaElement
  )
}

function validationMessage(control: Control): string {
  const state = control.validity
  if (state.valueMissing) {
    if (control instanceof HTMLSelectElement)
      return 'Bitte wähle einen Eintrag aus.'
    if (control.type === 'checkbox') return 'Bitte bestätige diese Auswahl.'
    if (
      control.autocomplete.includes('password') ||
      control.type === 'password'
    ) {
      return 'Bitte gib ein Passwort ein.'
    }
    if (control.autocomplete === 'username')
      return 'Bitte gib deinen Benutzernamen ein.'
    return 'Bitte fülle dieses Feld aus.'
  }
  if (state.typeMismatch)
    return control.type === 'email'
      ? 'Bitte gib eine gültige E-Mail-Adresse ein.'
      : 'Bitte gib eine gültige Internetadresse ein.'
  if (state.patternMismatch)
    return control.title || 'Bitte prüfe das Eingabeformat.'
  if (state.tooShort || state.tooLong) {
    if (
      control instanceof HTMLInputElement ||
      control instanceof HTMLTextAreaElement
    ) {
      return state.tooShort
        ? `Bitte gib mindestens ${control.minLength} Zeichen ein.`
        : `Es sind höchstens ${control.maxLength} Zeichen möglich.`
    }
  }
  if (control instanceof HTMLInputElement) {
    if (state.rangeUnderflow) return `Der Mindestwert ist ${control.min}.`
    if (state.rangeOverflow) return `Der Höchstwert ist ${control.max}.`
  }
  if (state.badInput || state.stepMismatch)
    return 'Bitte gib eine gültige Zahl ein.'
  return 'Bitte prüfe deine Eingabe.'
}

export function installGermanValidation(root: Document) {
  const translated = new WeakSet<Control>()

  root.addEventListener(
    'invalid',
    ({ target }) => {
      if (!isControl(target) || target.validity.customError) return
      target.setCustomValidity(validationMessage(target))
      translated.add(target)
    },
    true
  )

  function clearTranslation({ target }: Event) {
    if (isControl(target) && translated.delete(target))
      target.setCustomValidity('')
  }

  root.addEventListener('input', clearTranslation, true)
  root.addEventListener('change', clearTranslation, true)
}
