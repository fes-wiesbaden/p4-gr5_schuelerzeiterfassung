# Messverfahren: Reaktionszeit der Terminalansicht

Festgelegt für die Akzeptanz von [#38](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/38).

## Was gemessen wird

Die Reaktionszeit ist die Spanne vom Auflegen der Karte bis zu dem Moment, in
dem die Terminalansicht das Ergebnis sichtbar anzeigt. Sie zerfällt in vier
Abschnitte:

| Abschnitt | Von                    | Bis                               | Zuständiges Paket                                                              |
| --------- | ---------------------- | --------------------------------- | ------------------------------------------------------------------------------ |
| A         | Karte aufgelegt        | ESP32 hat die UID gelesen         | [#29](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/29) |
| B         | ESP32 sendet           | Backend hat `received_at` gesetzt | [#26](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/26) |
| C         | Scan verarbeitet       | SSE-Ereignis verlässt den Server  | [#26](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/26) |
| D         | SSE-Ereignis empfangen | Ergebnis steht auf dem Schirm     | **#37 – dieses Paket**                                                         |

Nur Abschnitt D gehört zur Terminalansicht. A bis C sind ohne ESP32 und Backend
nicht messbar und werden nachgereicht, sobald diese Pakete stehen.

## Verfahren für Abschnitt D

Voraussetzung: laufendes Frontend und ein Ereignisstrom, der `scan`-Ereignisse
liefert.

1. Terminalansicht unter `https://<TLS_HOST>:8443/terminal/<terminalnummer>`
   öffnen und die Entwicklerwerkzeuge auf den Reiter _Performance_ stellen.
2. Aufzeichnung starten.
3. Zwanzig Scans auslösen, dazwischen jeweils mindestens vier Sekunden warten,
   damit die Anzeige zwischendurch in den Bereitzustand zurückfällt.
4. Aufzeichnung beenden.
5. Je Scan die Spanne vom Eintreffen des `EventSource`-Ereignisses bis zum
   darauffolgenden _Paint_ ablesen.
6. Median und schlechtesten Wert notieren.

Alternativ ohne Entwicklerwerkzeuge, direkt in der Konsole der Terminalansicht:

```js
const source = new EventSource('/api/terminals/1/events')
source.addEventListener('scan', () => {
  const empfangen = performance.now()
  requestAnimationFrame(() =>
    console.log((performance.now() - empfangen).toFixed(1) + ' ms')
  )
})
```

## Zielwert

Abschnitt D bleibt im Median unter 100 ms. Die Ansicht wechselt bei einem
Ereignis nur einen Zustandswert und rendert drei Textknoten neu; ein höherer
Wert deutet auf ein Problem hin, nicht auf normale Last.

## Erhobene Werte

| Datum | Abschnitt | Median | Schlechtester Wert | Gerät |
| ----- | --------- | ------ | ------------------ | ----- |
| offen | D         | –      | –                  | –     |

Die Messung wird nachgetragen, sobald der Scan-Endpunkt aus
[#26](https://github.com/fes-wiesbaden/p4-gr5_schuelerzeiterfassung/issues/26)
`scan`-Ereignisse liefert. Bis dahin ist nur das Verfahren festgelegt.
