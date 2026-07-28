# Candidate NMC v2.34.1.0 — Test Report

## Résultat local

Toutes les validations demandées sont réussies, sans échec ni test ignoré.

| Commande | Résultat |
|---|---|
| `gradle testDebugUnitTest --rerun-tasks` | PASS — 291/291 |
| `gradle test --rerun-tasks` | PASS — debug + release, 582 exécutions |
| `python3 -m unittest tools/test_validate_sol_profile.py` | PASS — 9/9 |
| `gradle assembleDebug` | PASS |
| `gradle assembleRelease` | PASS |
| `gradle lintRelease` | PASS — zéro erreur lint |

Les 280 tests JVM historiques sont conservés. Onze tests NMC distincts ont été ajoutés, pour un total de 291 tests par variante.

## Golden master ETH

- Manifest : `app/src/test/resources/eth_v23321_golden_manifest.properties`
- SHA-256 : `cc443c78d8e1b6ff71920b57edb0cdddf329a83919a77957aca7adbbaee503bb`
- Digest global : `dd17b73ee7748179cac67f3b05592b4d53ce96e24f3766763054179c9a56b8d3`
- Parité 20 000 snapshots : **PASS**

## Mesures de performance et recorder

- Simulation status huit heures ETH + SOL : **17 799 octets** (limite : 100 000).
- Lectures JSONL dans `broadcastStatus()` : **0**.
- 10 000 événements `NO_EDGE` sur deux heures : **25 écritures** (premier + résumés cinq minutes + flush final).
- Événements lifecycle : jamais coalescés.
- Export réel d’un journal courant ≥64 Mio : **PASS**.
- Tampon maximal déclaré/mesuré par le contrat d’export : **8 192 octets**.
- Taille du ZIP compressé du fixture répétitif 64 Mio : **187 134 octets** ; ce chiffre faible est dû au caractère volontairement répétitif du fixture.
- Frames ETHUSDT et SOLUSDT : présentes et indépendantes.
- Cadence : refus à 4 999 ms, acceptation à 5 000 ms pour chaque symbole.
- Transitions feed identiques répétées : aucune duplication.
- Journal d’événements : aucune `MARKET_FRAME`.

## Export ZIP

Le test ouvre le ZIP, vérifie les 16 noms canoniques, l’absence de nom dupliqué, les SHA-256 uniques des entrées de données, les frames ETH/SOL, l’absence de frame dans `market_events.jsonl`, l’ordre `.1` puis courant et l’utilisation du streaming.

## APK locales

- Debug : `app/build/outputs/apk/debug/app-debug.apk`
  - taille : **4 077 486 octets**
  - SHA-256 : `4a926b6274f5c7ec1192ff77e5beb53d8c6f18ffe75d92565e893a3056272ce3`
- Release non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`
  - taille : **3 218 558 octets**
  - SHA-256 : `ccf4febda70e1438a5fb2b01ff0782b8d23603225ff713f25d7bccb16ae372b2`

Le manifeste APK debug confirme : package `com.ethscalper.cockpit.debug`, versionCode `23410`, versionName `2.34.1.0`, minSdk 26, targetSdk 35 et label `NMC`.

## GitHub Actions

Les métadonnées finales du run et de l’artefact sont ajoutées à la description de la PR nº 2 après le run, sans commit documentaire supplémentaire.
