# Gompashi

> *Maailman ainoa puhelimeen asennettava valtiollisesti tunnustamaton mittalaite, joka
> osoittaa kansalaiselle lyhimmän janoisen reitin kohti lähintä pohjoismaista
> alkoholimonopolia - oli se sitten Alko, Systembolaget tai Vinmonopolet.*

Hyvät naiset ja herrat, arvon nesteytyksen ystävät. Tiedoksenne saatetaan, että ihmiskunta
on vuosituhansien ajan eksynyt metsään, suohon ja paikallisvaaleihin pelkästään siksi,
ettei kukaan ole tiennyt, missä suunnassa lähin valtion juomahuolto sijaitsee. Tähän
epäkohtaan on nyt puututtu määrätietoisesti ja hieman jälkijättöisesti.

**Gompashi** on kompassi, joka ei osoita pohjoiseen. Pohjoisessa kun ei yleensä ole mitään.
Sen sijaan se osoittaa erään punaviinipullon - virka-asultaan *Gambina Cocktail* - muodossa
suoraan kohti lähintä valtion alkoholimyymälää. Pullo kääntyy, vipattaa ja osoittaa, ja
kansalainen seuraa. Yksinkertaista kuin valtionhallinto, mutta toimivaa.

Versiossa 1.1.0 laitos solmi historiallisen *Pohjolan janosopimuksen* ja laajensi
toimivaltansa diplomaattisesti rajojen yli: nyt rekisterissä komeilee yhteensä **1161
myymälää kolmesta kuningas- ja tasavallasta** - Suomen **Alko**, Ruotsin **Systembolaget**
ja Norjan **Vinmonopolet**. Pullo ei tunne tullimuodollisuuksia: Torniossa se osoittaa
empimättä Haaparannan puolelle, mikäli sieltä löytyy lähempi lohdutus.

## Toimintaperiaate (tieteellinen)

- Punapullonuoli osoittaa kohti lähintä monopolia ja reagoi puhelimen kääntelyyn herkästi
  kuin keskushallinnon mielipide tuoreimpaan gallupiin.
- Etäisyys lähimpään lohdutukseen ilmoitetaan metreinä tai kilometreinä, riippuen
  janon vakavuusasteesta.
- **Rajat ylittävä toimivalta:** laite ei välitä valtakunnanrajoista vaan osoittaa
  lähimpään myymälään, oli sen kyltissä sitten Alko, Systembolaget tai Vinmonopolet.
  Aukioloajat ja pyhät lasketaan kunkin maan oman virkapyhäkalenterin mukaan, jottei
  suomalainen juhannus erehdy sulkemaan ruotsalaista myymälää.
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

Pohjolan yhdistetty kapakkakartasto - kaikkien kolmen valtakunnan myymälät yhdessä
arkistomapissa - majailee tiedostossa `app/src/main/assets/alko_stores.json`. Kun
kartasto vanhenee, jalostuslaitos herätetään virka-ajan ulkopuolellakin yhdellä taikasanalla:

```bash
python3 tools/convert_stores.py
```

Skripti kutsuu koolle kolme erikielistä lähtöaineistoa, poistaa joukosta noutopisteet (ne
ovat pelkkiä tiskejä, joista tilaus noudetaan - eivät arvonsa tuntevia myymälöitä) sekä
pysyvästi suljetut, normalisoi kirjavat aukioloajat siistiksi viikkoaikatauluksi ja
toimittaa lopputuloksen kahtena kappaleena niin Android-appiin kuin `web/`-kansioon.
Pyhät hoidetaan erikseen, maakohtaisella tarkkuudella, `closed_dates.json`-listalta
(FI/SE/NO) - sillä jokainen valtakunta sulkee luukkunsa oman juhlakalenterinsa tahdissa:

| Maa | Monopoli | Mistä aineisto noudetaan |
|---|---|---|
| 🇫🇮 Suomi | Alko | `stores.json` (Alkon oma vienti, juurihakemistossa) |
| 🇸🇪 Ruotsi | Systembolaget | `tools/raw/systembolaget_mirror.json` (virallinen Site-data) |
| 🇳🇴 Norja | Vinmonopolet | `tools/raw/vinmonopolet_osm.json` (OpenStreetMap / Overpass) |

Norjan raakadatan saa tuoreena Overpassista koska tahansa. Ruotsin virallinen aarre lepää
APIn (`api-extern.systembolaget.se`) takana lukkojen ja ilmaisen avaimen päässä; tällä
hetkellä se on poimittu kohteliaasti yhteisön ylläpitämästä peilistä. Koko jalostamo
pyörii pelkällä Pythonin vakiokalustolla - ei ulkopuolisia riippuvuuksia, ei salaseuroja,
ei jäsenmaksuja.

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
| `MainViewModel` | Yhdistää kaiken ja päättää, mikä monopoli on lähinnä vuorossa |
| `MainScreen` / `MainActivity` | Esittää pullon, luvut ja painikkeet yleisölle |

Suunnittelu- ja toteutuspöytäkirjat arkistoituvat kansioon `docs/superpowers/`.

## Tekijänoikeudet, kunnianosoitukset ja muut juhlapuheet

- Suomen myymälätiedot: Alkon oma myymälärekisteri, oikeudet Alko Oy:lle - kiitos
  hyvästä palveluksesta jo vuodesta 1932.
- Ruotsin myymälätiedot: Systembolagetin avoin Site-API, oikeudet Systembolaget AB:lle.
  Heidän käyttöehtonsa sallivat aineiston julkaisun sovelluksessa, kunhan touhu ei sodi
  heidän kansanterveydellistä kutsumustaan vastaan - eikä osoitinpullo sitä tee.
- Norjan myymälätiedot: © OpenStreetMapin uupumattomat talkoolaiset, jotka kartoittivat
  Vinmonopoletit vapaaehtoisvoimin; lisenssi [ODbL](https://opendatacommons.org/licenses/odbl/).
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
