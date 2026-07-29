# NMC v2.34.2.0 — rapport de tests

## Baseline

La baseline au HEAD `305d054be7ec42d02a8f130f9a682dab3350cb0c` comptait 309 tests JVM distincts, tous réussis, zéro ignoré.

## Couverture ajoutée

La suite v2.34.2 ajoute des contrôles du timing public à 15 s, du scope shadow, de l’absence de publication anticipée, des fenêtres P02 inchangées, de la fixture 16 plans, des métriques Plans LONG/SHORT, des données manquantes, des leviers visuels, des actions de copie, du deep-link notification, des deux politiques de sizing et du lifecycle manuel TP/SL uniquement.

## Validations hors JVM

- Replay ETH canonique : PASS — 16 plans, P01 7, P02 9, TP 16, SL 0 ; fixture SHA-256 `4b49d0df47f17783a62c9ef1e7eeedd8f40e61438832f2d86e85a498d80fe7bd`.
- Validateur SOL : 9 tests Python, PASS.
- Golden ETH 20 000 snapshots : PASS ; manifest et digest inchangés.

## Résultats locaux finaux

Le dépôt ne contient pas de wrapper Gradle. Les tâches demandées ont donc été exécutées avec Gradle 8.10.2 et Java 17, avec les mêmes noms de tâches :

- `gradle testDebugUnitTest --rerun-tasks` : PASS, 322 tests, 0 échec, 0 erreur, 0 ignoré ;
- `gradle test --rerun-tasks` : PASS, 322 debug + 322 release, 0 échec, 0 erreur, 0 ignoré ;
- `python3 -m unittest tools/test_validate_sol_profile.py` : PASS, 9 tests ;
- `python3 tools/validate_eth_v2331_replay.py` : PASS ;
- `gradle assembleDebug` : PASS ;
- `gradle assembleRelease` : PASS ;
- `gradle lintRelease` : PASS, zéro erreur.

APK debug locale : `app/build/outputs/apk/debug/app-debug.apk`, 4 294 665 octets, SHA-256 `7cc2bb5dbdc5109b0531086b76868b3329ae0a01c6636d90d347bdcb244d75d2`.

APK release locale non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`, 3 225 206 octets, SHA-256 `ce24b51d033c0fba88d4826fc5bde8ba112dc4d9443b2d216e9c0cfade7b5e32`.

Le manifeste de l’APK debug annonce `com.ethscalper.cockpit.debug`, versionCode 23420, versionName 2.34.2.0, minSdk 26, targetSdk 35. Les métadonnées CI finales sont ajoutées à la description de la PR après le run afin d’éviter un commit documentaire supplémentaire.

Les résultats de recherche historiques ne constituent pas une garantie de performance future.
