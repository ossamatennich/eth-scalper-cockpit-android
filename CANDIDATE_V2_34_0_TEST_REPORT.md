# Candidate v2.34.0.1 — Test report

## Baseline et couverture

Les 210 tests historiques restent présents, sans suppression ni affaiblissement. La candidate compte **259 tests JVM distincts par variante** et **8 tests Python distincts**, soit **267 tests distincts**.

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

Les huit tests unitaires Python couvrent les bornes 1/120, les rejets 0/121, un calcul réel au-dessus de 120, le budget, SL min/max et TP floor/cap.

## Résultats techniques locaux

- `testDebugUnitTest --rerun-tasks` : PASS, 259 tests, 0 échec, 0 erreur, 0 ignoré.
- `test --rerun-tasks` : PASS, 259 debug + 259 release, toutes vertes.
- Tests Python : PASS, 8/8.
- `assembleDebug` : PASS.
- `assembleRelease` : PASS.
- `lintRelease` : PASS, 0 erreur.
- Debug : `app/build/outputs/apk/debug/app-debug.apk`, 4 557 624 octets, SHA-256 `7d16ffba5333a2c65942791cc81314704f3f0f30d6e68eb2369fe74879a93edb`.
- Release non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`, 3 628 805 octets, SHA-256 `fd4b21400a25512d78c5e1b7f339be0975713b053cd929bf076ed3e86df9b563`.
- Manifeste debug : package `com.ethscalper.cockpit.debug`, versionCode 23401, versionName 2.34.0.1, minSdk 26, target/compileSdk 35.

Les métadonnées finales du HEAD, du run Actions et de l'artefact CI seront placées dans la description de la PR après le run afin de respecter l'unique commit demandé.
