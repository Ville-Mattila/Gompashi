# Gompashi

> *Maailman ainoa puhelimeen asennettava valtiollisesti tunnustamaton mittalaite, joka
> osoittaa kansalaiselle lyhimmän janoisen reitin kohti lähintä Alkoa.*

Hyvät naiset ja herrat, arvon nesteytyksen ystävät. Tiedoksenne saatetaan, että ihmiskunta
on vuosituhansien ajan eksynyt metsään, suohon ja paikallisvaaleihin pelkästään siksi,
ettei kukaan ole tiennyt, missä suunnassa lähin Alko sijaitsee. Tähän epäkohtaan on nyt
puututtu määrätietoisesti ja hieman jälkijättöisesti.

**Gompashi** on kompassi, joka ei osoita pohjoiseen. Pohjoisessa kun ei yleensä ole mitään.
Sen sijaan se osoittaa erään punaviinipullon - virka-asultaan *Gambina Cocktail* - muodossa
suoraan kohti lähintä valtion alkoholimyymälää. Pullo kääntyy, vipattaa ja osoittaa, ja
kansalainen seuraa. Yksinkertaista kuin valtionhallinto, mutta toimivaa.

## Toimintaperiaate (tieteellinen)

- Punapullonuoli osoittaa kohti Alkoa ja reagoi puhelimen kääntelyyn herkästi kuin
  keskushallinnon mielipide tuoreimpaan gallupiin.
- Etäisyys lähimpään lohdutukseen ilmoitetaan metreinä tai kilometreinä, riippuen
  janon vakavuusasteesta.
- **Lähin / Toiseksi lähin** -valitsin niitä tilanteita varten, kun lähin on suljettu,
  loppuunmyyty tai muuten vain epämieluisa.
- Toimii ilman verkkoa. Myymälärekisteri matkaa mukana laitteessa, kuin eväät reppuselässä.

## Käyttöohje, jota kukaan ei lue

Pidä puhelinta **vaakatasossa, näyttö kohti taivasta**, aivan kuten oikeaa magneettikompassia
tai tarjotinta. Tämä ei ole mielipidekysymys vaan fysiikkaa: pystyyn nostettu kompassi
osoittaa korkeintaan kohti omaa turhautumistasi. Jos suunta heittelee, heiluta laitetta
ilmassa kahdeksikon muodossa - virallisesti tätä kutsutaan *kalibroinniksi*, kansanomaisesti
*hölmöilyksi bussipysäkillä*.

## Asennusta edeltävät hallinnolliset edellytykset

Hakemuksen käsittely edellyttää seuraavia liitteitä:

- JDK 17 (seitsemäntoista, ei enempää eikä vähempää)
- Android SDK: platform 35 ja build-tools 35.0.0
- Laite tai emulaattori, jossa on Google Play -palvelut sijainnin selvittämistä varten

## Rakentaminen eli kakun leipominen

```bash
./gradlew :app:assembleDebug       # leivo debug-APK
./gradlew :app:testDebugUnitTest   # tarkasta että matematiikka ei valehtele
./gradlew :app:installDebug        # siirrä teos kytkettyyn laitteeseen
```

> ### ⚠️ VIRALLINEN VAROITUS ei-ASCII-poluista ⚠️
> Mikäli projektinne majailee kansiossa, jonka nimessä komeilee ei-ASCII-merkki - kuten
> sivistynyt skandinaavinen **ö** sanassa `Työt` - Androidin yksikkötestikone menee
> lakkoon ja julistaa `ClassNotFoundException`. Tämä on tunnettu ilmiö, ja siihen on
> kehitetty byrokraattinen kiertotie: pystytetään ASCII-polkuun *risteysasema* (junction)
> ja ajetaan rakennustyöt sen kautta:
> ```
> mklink /J C:\dev\Gompashi C:\polku\jossa-on-ö\Gompashi
> ```
> Itse sovellus (`assembleDebug`) sentään kääntyy myös ö-polussa, sillä
> `gradle.properties`-tiedostoon on liitetty asianmukainen poikkeuslupa
> (`android.overridePathCheck=true`). Pykälä löytyy, kun sitä tarpeeksi etsii.

## Myymälärekisterin virkistäminen

Alkojen sijainnit ja aukioloajat sijaitsevat tiedostossa `app/src/main/assets/alko_stores.json`.
Lähtöaineisto on Alkon oma myymälävienti `stores.json` (juurihakemistossa). Kun valtakunnan
kapakkakartasto vanhenee, jalostuslaitos käynnistetään yhdellä taikasanalla:

```bash
python tools/convert_stores.py
```

Skripti lukee `stores.json`-arkin, karsii joukosta noutopisteet (ne, joista vain noudetaan
tilaus, eivät ole oikeita myymälöitä) ja pysyvästi suljetut, poimii viikkoaikataulut sekä
seuraavan kymmenen päivän päiväkohtaiset poikkeukset (pyhät ja erikoispäivät), ja kirjoittaa
tuloksen sekä Android-appiin että `web/`-kansioon. Pelkkää Pythonin vakiokalustoa - ei
ulkopuolisia riippuvuuksia, ei salaseuroja, ei jäsenmaksuja.

## Laitoksen sisäinen organisaatiokaavio

Liiketoimintalogiikka on sijoitettu siisteihin, erikseen koeteltaviin virkahuoneisiin,
erillään herkästä Android-sensori- ja sijaintiosastosta:

| Yksikkö | Toimenkuva |
|---|---|
| `GeoUtils` | Laskee etäisyyden ja suuntiman pallon pinnalla, valittamatta |
| `AlkoStore` / `AlkoRepository` | Säilöö ja jäsentää myymälätiedot arkistokaapista |
| `NearestStoreFinder` | Asettaa kapakat jonoon etäisyyden mukaan, kuten kuuluukin |
| `DistanceFormat` | Pukee metrit ja kilometrit ihmisymmärrettävään virka-asuun |
| `CompassProvider` | Kuulostelee laitteen suuntaa magneettikentästä |
| `LocationProvider` | Tiedustelee kansalaisen olinpaikan satelliiteilta |
| `MainViewModel` | Yhdistää kaiken ja päättää, kumpi Alko on vuorossa |
| `MainScreen` / `MainActivity` | Esittää pullon, luvut ja painikkeet yleisölle |

Suunnittelu- ja toteutuspöytäkirjat arkistoituvat kansioon `docs/superpowers/`.

## Tekijänoikeudet, kunnianosoitukset ja muut juhlapuheet

- Myymälätiedot ja aukioloajat: peräisin Alkon omasta myymälärekisteristä.
  Oikeudet kuuluvat Alko Oy:lle; tässä niitä vain osoitellaan pullolla.
- `compass_needle.png`: *Gambina Cocktail* -pullo, ylennetty kunniakkaaseen
  kompassinuolen virkaan.
- `title.svg`: talon oma vaakuna, taottu vektoripajassa.
- Etäisyyslukeman pisteröity loiste: **Bitcount Single** -kirjasin, © sen tekijät,
  lisenssi SIL Open Font License 1.1 (`licenses/BitcountSingle-OFL.txt`).

## Vastuuvapauslauseke

Gompashi ei kehota nauttimaan alkoholia, vaan ainoastaan tietämään, missä suunnassa sitä
teoriassa olisi. Valmistaja ei vastaa eksymisistä, pettymyksistä, sulkemisajoista,
hyllyväleihin kadonneista iltapäivistä eikä siitä, että kävelit innoissasi suoraan
lammikkoon pullonkuvaa tuijottaen. Nauti vastuullisesti - niin nestettä kuin navigointia.
