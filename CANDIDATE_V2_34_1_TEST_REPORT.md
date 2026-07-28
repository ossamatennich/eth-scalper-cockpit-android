# Candidate NMC v2.34.1.1 — Test Report

## Résultat local

Toutes les validations demandées sont réussies, sans échec ni test ignoré.

| Commande | Résultat |
|---|---|
| `gradle testDebugUnitTest --rerun-tasks` | PASS — 309/309 |
| `gradle test --rerun-tasks` | PASS — debug + release, 618 exécutions |
| `python3 -m unittest tools/test_validate_sol_profile.py` | PASS — 9/9 |
| `gradle assembleDebug` | PASS |
| `gradle assembleRelease` | PASS |
| `gradle lintRelease` | PASS — zéro erreur lint |

Les 291 tests JVM de NMC v2.34.1.0 sont conservés. Dix-huit tests ciblés d’alerte sonore sont ajoutés, pour un total de 309 tests distincts par variante et 618 exécutions debug/release.

## Correctif d’alerte sonore

- `notifyTestAlert()` utilise le même `postAudibleFinalSignalAlert(...)` que les signaux finaux ETH et SOL.
- canal sonore : `nmc_final_signal_loud_v1` ; ancien canal v2.33.0 absent du chemin audible ;
- test répété : toujours éligible, ID réservé, aucune écriture dans la déduplication métier ;
- premier plan final : audible ; répétition d’une même signature : silencieuse ;
- déduplication réelle écrite uniquement après `NotificationManager.notify(...)` ;
- échec : `SIGNAL_AUDIBLE_ALERT_FAILED`, aucune signature consommée et aucun champ du plan muté ;
- restaurations, TP, SL et test vibration : silencieux.

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
  - taille : **4 280 927 octets**
  - SHA-256 : `ca459b38dedb118f8a061d77eba3d82698912ad839dc52887d08e6fa0ba3a0bf`
- Release non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`
  - taille : **3 220 674 octets**
  - SHA-256 : `cfe14f378b258f8461dd19d6d00044e6ecff9953bac7a6e4c585f586035f0e78`

Le manifeste APK debug confirme : package `com.ethscalper.cockpit.debug`, versionCode `23411`, versionName `2.34.1.1`, minSdk 26, targetSdk 35 et label `NMC`.

## Test Android

L’AVD Android 35 installé localement a été lancé avec et sans accélération matérielle, mais il s’est arrêté avant d’apparaître dans `adb devices` ; aucune cible Android opérationnelle n’était donc disponible pour installer l’APK et déclencher `ACTION_TEST_ALERT`. Les tests JVM vérifient le canal, `audible=true`, le son, la vibration, la séquence de déduplication et les chemins silencieux. Aucun son n’est déclaré « entendu » sans matériel audio Android réel.

## GitHub Actions

Les métadonnées finales du run et de l’artefact sont ajoutées à la description de la PR nº 2 après le run, sans commit documentaire supplémentaire.
