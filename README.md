# AutoRynek (Fabric 1.21.4) – wersja 0.1

## Budowanie
1. Zainstaluj JDK 21.
2. Brakuje `gradlew` – skopiuj go (`gradlew`, `gradlew.bat`, folder `gradle/`) z szablonu
   https://fabricmc.net/develop/template/ albo uruchom `gradle wrapper --gradle-version 8.12`.
3. `./gradlew build` → gotowy plik w `build/libs/autorynek-0.1.0.jar` (nie `-sources`).
4. Wrzuć do `mods/` razem z Fabric API dla 1.21.4.
5. W IntelliJ: otwórz folder jako projekt Gradle, potem `./gradlew genSources` i `runClient`.

## Obsługa
- **L** w dowolnym oknie z ekwipunkiem – dodaje przedmiot pod kursorem (id + nazwa + enchanty).
- **J** w oknie rynku – zapisuje zrzut slotów do `config/autorynek-dump.txt` (numery slotów + lore z ceną).
- `/autorynek start | analyze | stop | reset | resetstats`
- `/autorynek margin <nr> <kwota>` – marża przedmiotu (nr jak w HUD)
- `/autorynek query <nr> <tekst>` – tekst używany w `/ah search`
- `/autorynek remove <nr>`

## Kalibracja (obowiązkowa)
Otwórz `/ah search coś`, naciśnij **J**, odczytaj z `autorynek-dump.txt` numery slotów i wpisz w
`config/autorynek.json` (sekcja `settings`): `refreshSlot`,
`confirmSlot`, `marketTitleKeyword`, a gdy cena nie jest odczytywana – `pricePattern`.
`successKeywords` = fragmenty wiadomości serwera po udanym zakupie (małe litery).

## Wzór ceny skupu
`cena_skupu = floor(najniższa_cena * 0.97) - marża`

## Jar bez instalowania czegokolwiek (GitHub Actions)
1. Załóż puste repozytorium na GitHubie i wrzuć do niego zawartość tego folderu (razem z `.github`).
2. Zakładka **Actions** → workflow `build` → po zakończeniu pobierz artefakt `autorynek-jar`.
3. W środku użyj pliku `autorynek-0.1.0.jar` (nie `-sources`).

## Sortowanie i analiza (wersja 0.2)
- Przycisk sortowania to przedmiot `minecraft:repeater` w dolnym rzędzie okna rynku (ustawienie `sortItemId`).
  Bot klika go **prawym przyciskiem**, aż tryb to „Najniższej ceny". Jeśli potrafi odczytać aktualny tryb z opisu,
  klika dokładnie tyle razy, ile trzeba; w przeciwnym razie klika `sortClicks` razy (domyślnie 2).
- Cena przedmiotu z analizy = średnia ceny ZA SZTUKĘ z `analysisOffers` (domyślnie 3) najtańszych ofert ze wszystkich stron.
- Podczas analizy bot sam zapisuje zrzuty okna: `config/autorynek-dump-analiza.txt` (układ po sortowaniu)
  oraz `config/autorynek-dump-blad.txt` (gdy nie odczytał cen).

## Dokładne dopasowanie przedmiotów (komponenty)
- Przedmiot jest rozpoznawany po: id, nazwie, enchantach i **komponentach** (custom_data, item_model, opis itd.),
  więc kupowany jest dokładnie ten sam, także customowy przedmiot serwera.
- Z porównania wyłączone są komponenty z `ignoredComponents` (domyślnie `minecraft:damage`, czyli wytrzymałość)
  oraz część opisu dopisana przez rynek (od linii zawierającej tekst z `listingLoreMarkers`, domyślnie „Wystawił").
- Gdy oferty tego samego przedmiotu nie pasują, naciśnij **J** w oknie rynku i porównaj w zrzucie linie „komponenty":
  komponent, którego skrót różni się między ofertami (np. `minecraft:custom_data`), dopisz do `ignoredComponents`.
- Stare wpisy sprzed tej wersji porównują tylko id/nazwę/enchanty. Naciśnij **L** na przedmiocie, żeby uzupełnić komponenty.

## Strony rynku i cena za sztukę (wersja 0.4)
- Bot przegląda wszystkie strony wyszukiwania (zielony barwnik „Następna strona" w dolnym rzędzie; numer strony czytany z tytułu „Rynek (2/9)").
- Każda oferta jest przeliczana na cenę za sztukę (cena / liczba sztuk), np. 3 szt. za 1000$ = 333$/szt.
  Cena skupu jest za sztukę, więc przy skupowaniu porównywana jest z ceną za sztukę oferty (kupowana jest cała oferta).
- Analiza: średnia z `analysisOffers` najtańszych (za sztukę) ofert. Skupowanie: `buyMaxPages` stron na cykl.
- Ustawienia: `maxPages`, `buyMaxPages`, `analysisScanAllPages`, `nextPageItemId`, `nextPageNameContains`, `nextPageSlot`, `pageTimeoutTicks`.
