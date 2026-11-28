// JSDOM stellt die im Kalender verwendeten Media Queries nicht bereit.
Object.defineProperty(globalThis, 'matchMedia', {
  configurable: true,
  value: (media: string) => ({
    media,
    matches: false,
    addEventListener() {},
    removeEventListener() {}
  })
})

// JSDOM implementiert das Scrollen nicht und meldet sonst bei jedem Aufruf einen Fehler
Object.defineProperty(window, 'scrollTo', {
  configurable: true,
  writable: true,
  value: () => {}
})
