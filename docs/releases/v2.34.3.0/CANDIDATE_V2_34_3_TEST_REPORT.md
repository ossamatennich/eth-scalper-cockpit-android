# NMC v2.34.3.0 — rapport de validation

## Tests locaux

Le dépôt n’inclut pas de wrapper. Gradle 8.10.2 a été exécuté avec le JBR Java 17 d’Android Studio, avec les tâches exactes demandées.

- `testDebugUnitTest --rerun-tasks` : PASS, 361 tests distincts, 0 échec, 0 erreur, 0 ignoré ;
- `test --rerun-tasks` : PASS, 361 debug + 361 release, soit 722 exécutions, 0 échec, 0 erreur, 0 ignoré ;
- historique conservé : 322 tests ; nouveaux tests v2.34.3 : 39 ;
- `python3 -m unittest tools/test_validate_sol_profile.py` : 9 tests PASS (localement lancé avec l’exécutable Python embarqué direct, la CI emploie la commande `-m unittest` exacte) ;
- `python3 tools/validate_eth_v2331_replay.py` : PASS, 16 plans, 7 P01, 9 P02, 16 TP, 0 SL, fixture SHA-256 `4b49d0df47f17783a62c9ef1e7eeedd8f40e61438832f2d86e85a498d80fe7bd` ;
- `python3 tools/validate_structural_stop_research.py` : PASS, paquet SHA vérifié, 14 sessions brutes, 16 plans issus de 9 sessions tradées, aucun look-ahead, aucun plan avant 15 s ;
- golden manifest ETH inchangé, SHA-256 `cc443c78d8e1b6ff71920b57edb0cdddf329a83919a77957aca7adbbaee503bb`, digest global attendu `dd17b73ee7748179cac67f3b05592b4d53ce96e24f3766763054179c9a56b8d3` ;
- `assembleDebug` : PASS ;
- `assembleRelease` : PASS ;
- `lintRelease` : PASS, 0 erreur (18 avertissements historiques non bloquants).

## APK locales

- Debug : `app/build/outputs/apk/debug/app-debug.apk`, 4 097 322 octets, SHA-256 `1b29826591aeede9d6f1e516e57b5eff2315c2239270478e59406bf036a2f9bc`.
- Release non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`, 3 232 878 octets, SHA-256 `87987fa8f49e58c72820ecbf327682e38e8bb1aeb535787d9ec7689853a077e3`.

## CI

Les identifiants du run et de l’artefact ainsi que les SHA-256 CI sont ajoutés à la description de la PR nº 2 après le succès réel du workflow, sans commit documentaire supplémentaire.
