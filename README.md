# Kulturadar

Android aplikace pro objevování kulturních akcí, divadel a kina.

## V1
- Compose UI ve stylu Knižního radaru
- Objevování velkými kartami
- Milované / Nenáviděné
- filtry: typ, město, hledání, datum, zdarma, max. cena
- detail akce
- lokální ukládání reakcí
- připravený Ticketmaster Discovery API adaptér (`TICKETMASTER_API_KEY` přes `-P` nebo secret v CI)
- demo data fungují i bez API klíče

## Build
Projekt míří na Android API 36 / minSdk 24. GitHub Actions sestavuje debug APK a release AAB jako artifact.
