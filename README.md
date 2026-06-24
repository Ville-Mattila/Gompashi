# Gompashi

Minimalistinen Android-appi, joka näyttää suunnan ja etäisyyden lähimpään Alkoon.
"Kompassinuolena" on Gambina Cocktail -pullo, joka pyörii osoittaen fyysisesti kohti
valittua Alkoa. Toggle vaihtaa lähimmän ja toiseksi lähimmän Alkon välillä.

## Ominaisuudet

- Pullonuoli osoittaa kohti Alkoa ja reagoi puhelimen kääntämiseen (kompassi + suuntima).
- Etäisyys lähimpään Alkoon metreinä/kilometreinä.
- Toggle: **Lähin** / **Toiseksi lähin** Alko.
- Toimii offline — myymälädata on bundlattu sovellukseen.

## Vaatimukset

- JDK 17
- Android SDK (platform 35, build-tools 35.0.0)
- Laite/emulaattori, jossa Google Play Services (sijaintia varten)

## Rakentaminen

```bash
./gradlew :app:assembleDebug       # rakenna debug-APK
./gradlew :app:testDebugUnitTest   # aja JVM-yksikkötestit
./gradlew :app:installDebug        # asenna kytkettyyn laitteeseen/emulaattoriin
```

> **Windows + ei-ASCII-polku:** jos projekti sijaitsee polussa, jossa on ei-ASCII-merkki
> (esim. `Työt`), Androidin JVM-yksikkötestien ajo epäonnistuu `ClassNotFoundException`-
> virheeseen. Kierrä ongelma luomalla ASCII-polkuun junction ja aja buildit sen kautta:
> ```
> mklink /J C:\dev\Gompashi C:\polku\jossa-on-ö\Gompashi
> ```
> Sovellus itse (`assembleDebug`) kääntyy myös suoraan ei-ASCII-polussa
> (`android.overridePathCheck=true` on `gradle.properties`-tiedostossa).

## Myymälädatan päivitys

Data on tiedostossa `app/src/main/assets/alko_stores.json`. Päivitä ajamalla:

```bash
python tools/fetch_alko.py
```

Skripti hakee Alko-myymälät OpenStreetMapista (Overpass API) ja kirjoittaa JSON:in.
Vain Python-vakiokirjasto, ei riippuvuuksia.

## Arkkitehtuuri

Liiketoimintalogiikka on puhtaissa, JVM-testattavissa luokissa erillään Android-
sensori-/sijaintikerroksesta:

| Yksikkö | Vastuu |
|---|---|
| `GeoUtils` | Haversine-etäisyys ja suuntima (puhdas) |
| `AlkoStore` / `AlkoRepository` | Datamalli ja JSON-parsinta |
| `NearestStoreFinder` | Etäisyyden mukaan järjestetty myymälälista |
| `DistanceFormat` | Etäisyyden muotoilu |
| `CompassProvider` | Laitteen atsimuutti (`TYPE_ROTATION_VECTOR`) |
| `LocationProvider` | Sijainti (`FusedLocationProviderClient`) |
| `MainViewModel` | Yhdistää tilan + lähin/toiseksi lähin -valinta |
| `MainScreen` / `MainActivity` | Compose-UI ja lupavirta |

Suunnittelu- ja toteutusdokumentit: `docs/superpowers/`.

## Attribuutio & lisenssit

- Myymälädata: © OpenStreetMap-tekijät, lisenssi [ODbL](https://opendatacommons.org/licenses/odbl/).
- `compass_needle.png`: Gambina Cocktail -pullo, käytetään kompassinuolena.
