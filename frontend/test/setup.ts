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
