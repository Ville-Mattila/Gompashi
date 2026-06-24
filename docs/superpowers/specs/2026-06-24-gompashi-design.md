# Gompashi — suunnitteludokumentti

**Päivä:** 2026-06-24
**Tila:** Hyväksytty (suunnitelma), valmis toteutussuunnitelmaa varten

## Yhteenveto

Gompashi on minimalistinen yhden näytön Android-sovellus, joka näyttää suunnan ja
etäisyyden lähimpään Alkoon. "Kompassinuolena" käytetään `compass-needle.png`-kuvaa
(Gambina Cocktail -pullo), joka pyörii osoittamaan fyysisesti kohti valittua Alkoa.
Käyttäjä voi vaihtaa kohteen lähimmän ja toiseksi lähimmän Alkon välillä.

## Tavoitteet

- Näytä lähimmän Alkon suunta ja etäisyys mahdollisimman yksinkertaisella UI:lla.
- Pullonuoli osoittaa fyysisesti kohti Alkoa (reagoi puhelimen kääntämiseen).
- Toggle: "Lähin" / "Toiseksi lähin" Alko.
- Toimii offline (data paketoitu sovellukseen).

## Ei-tavoitteet (YAGNI)

- Ei karttanäkymää, ei reititystä, ei myymälätietoja (aukioloajat ym.).
- Ei asetuksia, valikoita eikä käyttäjätilejä.
- Ei reaaliaikaista myymälädatan päivitystä (päivittyy sovelluspäivityksen mukana).

## Teknologiavalinnat

- **Kieli/UI:** Kotlin + Jetpack Compose
- **minSdk:** 26, **targetSdk:** viimeisin vakaa
- **Build:** Gradle (Kotlin DSL)
- **Sijainti:** `com.google.android.gms:play-services-location` (FusedLocationProviderClient)
- **Suunta:** `SensorManager`, `TYPE_ROTATION_VECTOR`
- **Data:** staattinen JSON `app/src/main/assets/alko_stores.json`, generoitu kerran
  OpenStreetMap/Overpass-haulla

## Arkkitehtuuri

Erilliset, yhden vastuun yksiköt, jotka kommunikoivat selkeiden rajapintojen kautta.
Liiketoimintalogiikka pidetään puhtaissa, JVM-testattavissa luokissa erillään Android-
sensori-/sijaintikerroksesta.

### Komponentit

| Komponentti | Vastuu | Riippuvuudet |
|---|---|---|
| `AlkoStore` | Datamalli: `name`, `lat`, `lon` | — |
| `AlkoRepository` | Lataa ja parsii `alko_stores.json` → `List<AlkoStore>` | assets |
| `GeoUtils` | Puhtaat funktiot: `distanceMeters(a,b)` (haversine), `bearingTo(a,b)` (astetta pohjoisesta) | — |
| `NearestStoreFinder` | Annettu sijainti + lista → etäisyyden mukaan järjestetty lista (vähintään top-2) etäisyyksineen | `GeoUtils` |
| `CompassProvider` | `TYPE_ROTATION_VECTOR` → laitteen atsimuutti asteina, low-pass-tasoitus; `Flow<Float>` | `SensorManager` |
| `LocationProvider` | `FusedLocationProviderClient` → `Flow<Location>`; lupien käsittely | Play Services |
| `MainViewModel` | Yhdistää sijainnin + kompassin + lähimmät myymälät → UI-tila | yllä olevat |
| `MainScreen` | Compose-näkymä: pyörivä pullo, etäisyys, nimi, toggle, tila/virheviestit | `MainViewModel` |

### UI-tila (`MainViewModel`)

- `selectedRank: Int` (0 = lähin, 1 = toiseksi lähin)
- Johdetut: `targetStore`, `rotationDeg = bearingToStore − deviceAzimuth`,
  `distanceText` (m / km), `storeName`
- Tilaliput: `permissionGranted`, `locationAvailable`, `hasCompass`, `loading`, `error`

### Datavirta

```
GPS (LocationProvider) ─┐
                        ├─> NearestStoreFinder ─> järjestetty lista
assets (AlkoRepository) ┘                          │
                                                   v
Kompassi (CompassProvider) ──> atsimuutti ──> MainViewModel
                                                   │
                            rotationDeg = bearing(selectedRank) − atsimuutti
                                                   v
                                              MainScreen (pullo pyörii)
```

Käyttäjä kääntää puhelinta → atsimuutti muuttuu → pullo pysyy osoittaen Alkoa kohti.
Käyttäjä vaihtaa togglea → `selectedRank` vaihtuu → kohde, suuntima ja etäisyys päivittyvät.

## UI

Yksi näyttö, pystysuunta:

- Keskellä: `compass-needle.png` `Modifier.rotate(rotationDeg)`-modifierilla.
- Pullon ala-/yläpuolella: etäisyysteksti (esim. "1,2 km") ja Alkon nimi.
- Toggle: kaksitilainen segmentoitu valinta "Lähin / Toiseksi lähin".
  - Jos myymälöitä on vain yksi, "Toiseksi lähin" on disabloitu.
- Tilanäkymät: "Haetaan sijaintia…", lupapyyntö, "Ei magnetometriä".

## Virhetilat

- **Sijaintilupa evätty:** selkeä viesti + nappi joka käynnistää lupapyynnön / avaa asetukset.
- **Ei sijaintia vielä / GPS pois:** "Haetaan sijaintia…" -lataustila.
- **Ei magnetometriä:** näytä etäisyys ja suuntima-asteet tekstinä; pullo ei pyöri (graceful degradation).
- **JSON-latausvirhe:** virheilmoitus (ei odoteta tapahtuvan, data on paketissa).

## Testaus

- **JVM-yksikkötestit (nopeat, ei emulaattoria):**
  - `GeoUtils`: haversine ja bearing tunnetuilla koordinaateilla (referenssiarvot).
  - `NearestStoreFinder`: järjestys, top-2, tasapelit, yhden myymälän tapaus.
  - `AlkoRepository`: JSON-parsinta.
- Sensori-/sijaintikerrokset pidetään ohuina ja manuaalisesti testattavina; logiikka asuu puhtaissa yksiköissä.

## Datan generointi

Kertaluontoinen skripti (`tools/fetch_alko.*`) hakee Overpass-API:sta Alko-myymälät
(esim. `shop=alcohol` + `name~Alko`, koko Suomi) ja kirjoittaa
`app/src/main/assets/alko_stores.json`. OSM-data on ODbL-lisensoitua → attribuutio
README:hen.

## Projektin rakenne

```
Gompashi/
  app/
    src/main/
      assets/alko_stores.json
      res/drawable/compass_needle.png  (siirretty/uudelleennimetty)
      java/.../{AlkoStore,AlkoRepository,GeoUtils,NearestStoreFinder,
               CompassProvider,LocationProvider,MainViewModel,MainScreen,MainActivity}.kt
      AndroidManifest.xml  (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION)
    src/test/  (JVM-yksikkötestit)
  tools/fetch_alko.*
  docs/superpowers/specs/2026-06-24-gompashi-design.md
  build.gradle.kts, settings.gradle.kts, .gitignore, README.md
```

## GitHub

Yksityinen repo `Gompashi`, luodaan `gh` CLI:llä (tili: Ville-Mattila) suunnitelman
hyväksynnän jälkeen.

## Attribuutio & lisenssit

- Myymälädata: © OpenStreetMap-tekijät, ODbL.
- `compass-needle.png` (Gambina Cocktail -pullo) käytetään kompassinuolena.
