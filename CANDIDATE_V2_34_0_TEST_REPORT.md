# Candidate v2.34.0.3 — Test report

## Baseline et couverture

Les 270 tests JVM existants restent présents, sans suppression ni affaiblissement. La candidate compte **280 tests JVM distincts par variante** et **9 tests Python distincts**, soit **289 tests distincts**.

Couverture ajoutée : vrai golden master ETH issu du commit historique, routage service bookTicker/kline/aggTrade, préchargement et fallback génériques, troisième symbole sans branche spécifique, admission SOL structurelle, scénarios simultanés ETH/SOL, notifications distinctes, terminaux isolés, reset conservant deux plans, feeds et mémoire opposée isolés, et P02 sans mutation de `lastP01ConfirmedAt`.

## Parité ETH

- Source : `5e00f3f88bf2da5237ae7f8c0d851aa0fb4fe251`
- Graine : `23321042`
- Snapshots : 20 000
- Résultat : PASS, digests exacts pour toutes les familles couvertes.
- SHA-256 du manifest golden : `cc443c78d8e1b6ff71920b57edb0cdddf329a83919a77957aca7adbbaee503bb`
- Digest global : `dd17b73ee7748179cac67f3b05592b4d53ce96e24f3766763054179c9a56b8d3`

## Validation officielle SOL

- Source : archives publiques officielles Binance Futures USD-M avec CHECKSUM SHA-256.
- Période disponible : 2024-07-01 au 2026-07-26 ; l'archive quotidienne du 2026-07-27 renvoyait HTTP 404 au moment du test.
- Archives : 150 ZIP.
- Bougies : 1 088 640 par symbole ETHUSDT, SOLUSDT et BTCUSDT.
- Observations du ratio : 1 087 585.
- Trous : 0 ; doublons identiques : 0 ; conflits : 0.
- Manifest interne SHA-256 : `7f08e6a4f65a3aba56223e517d4166c35e03f472e683a1d7a760be9daf57a5fc`.
- Ratio relatif SOL/ETH : p10 0.957939 ; p25 1.098397 ; médiane 1.283498 ; p75 1.528929 ; p90 1.835800.
- Corrélation des rendements 1m ETH/SOL : 0.768061.
- Contrôle médiane [0.85 ; 1.35] : PASS.
- Calculs rejetés explicitement hors 1..120 : 15 496 ; aucun clamp silencieux.

Les huit tests unitaires historiques Python couvrent les bornes 1/120, les rejets 0/121, un calcul réel au-dessus de 120, le budget, SL min/max et TP floor/cap. Le neuvième test charge le manifest versionné, recalcule son SHA canonique et vérifie counts, gaps, doublons, `quantityRejectionsAboveSafetyCap=15496` et la cohérence du rapport.

## Résultats techniques locaux

- `testDebugUnitTest --rerun-tasks` : PASS, 280 tests, 0 échec, 0 erreur, 0 ignoré.
- `test --rerun-tasks` : PASS, 280 debug + 280 release, 0 échec, 0 erreur, 0 ignoré.
- `python3 -m unittest tools/test_validate_sol_profile.py` : PASS, 9/9.
- `assembleDebug` : PASS.
- `assembleRelease` : PASS.
- `lintRelease` : PASS, 0 erreur.
- Debug : `app/build/outputs/apk/debug/app-debug.apk`, 4 573 244 octets, SHA-256 `90b782118f5107ca27ccc0d0c9223502fd95c1ad27cc78aeac721cbc9ba8e2e1`.
- Release non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`, 3 641 797 octets, SHA-256 `5f739dac39ee2166eb3a69ca6bd1ba1871caf5fddabaa83fbf5239855b02f391`.
- Manifeste debug : package `com.ethscalper.cockpit.debug`, versionCode 23403, versionName 2.34.0.3, minSdk 26, target/compileSdk 35.

## Diagnostics multi-marchés vérifiés

Les tests couvrent une admission SOL acceptée et rejetée, le diagnostic historique générique, P01/P02 avec OLS60 et flows 15/30/60/120, plans/TP/SL SOL, reset/restauration ETH+SOL, deux plans dans l'export, non-comptabilisation des candidats, troisième profil, 19 fichiers ZIP, versions courantes, IDs de notification distincts et terminaux exclusivement TP/SL.

## Bornage diagnostics et status

- Simulation de deux heures ETH + SOL : PASS.
- Taille maximale mesurée du status final : **18 917 octets**, sous les limites 100 000/200 000.
- `MARKET_FRAME` absent de `eventMaps()`, `eventsAfter()` et du journal persistant d'événements.
- Cadence persistante vérifiée aux frontières 4 999/5 000 ms.
- Rotation vérifiée : maximum 64 Mio, une génération `.1`, lecture `.1` puis courant, reset des deux.
- Export reconstruit depuis les journaux persistants et non depuis `status.json`.
- `PLAN_CONFIRMED` + `PLAN_RESTORED` = un seul trade confirmé et un plan restauré séparé.
- Deux diagnostics partageant un timestamp mais ayant une identité différente sont conservés.

Les métadonnées finales du HEAD, du run Actions et de l'artefact CI seront placées dans la description de la PR après le run afin de respecter l'unique commit demandé.
