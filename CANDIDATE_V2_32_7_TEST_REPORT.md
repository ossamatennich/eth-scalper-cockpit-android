# Candidate v2.33.0 — rapport de tests

> Le nom historique du fichier est conservé. Tous les résultats concernent **ETH Scalper Cockpit v2.33.0 — Dual Sleeve Dynamic Risk**.

## Environnement

- Windows / PowerShell
- Android SDK : `C:\Users\Tenni\AppData\Local\Android\Sdk`
- JDK : Android Studio JBR 17
- Gradle : 8.10.2, distribution officielle temporaire, le dépôt ne fournissant pas de wrapper fonctionnel
- `versionCode=23300`, `versionName=2.33.0`, `minSdk=26`, `targetSdk=35`

## Commandes exécutées

```powershell
gradle testDebugUnitTest --rerun-tasks
gradle test --rerun-tasks
gradle assembleDebug assembleRelease lintRelease --rerun-tasks
```

Les cinq tâches obligatoires `testDebugUnitTest`, `test`, `assembleDebug`, `assembleRelease` et `lintRelease` ont donc toutes été exécutées. Les processus utilisaient un seul worker avec `JAVA_HOME` et `ANDROID_HOME` limités à la session.

## Résultats unitaires

- 175 tests distincts ;
- debug : 175/175 réussis ;
- release : 175/175 réussis ;
- passe globale : 350 exécutions réussies ;
- 0 échec, 0 erreur, 0 test ignoré.

La couverture comprend notamment : toutes les frontières P01/P02 à ±epsilon ; phases P01 précoce/différée et expiration ; apparition C1/C2 sans duplication ; silence et bornes P02 20/45 s ; OLS60 croissant/décroissant/plat/incomplet et symétrie LONG/SHORT ; modes TREND/REVERSAL ; quotes zéro/NaN/infini ; formules SL/TP exactes ; rejets stop/RR ; sizing 1/2/3 ETH avec réserve 2,35 ; perte modélisée ≤ 10 USDT ; réarmement terminal persistant ; lifecycle TP/SL only ; RANGE_FADE diagnostic ; IA non décisionnelle et `realTradingAllowed=false`.

## Build et lint

- `assembleDebug` : succès ;
- `assembleRelease` : succès ;
- `lintRelease` : succès ;
- rapport lint : `app/build/reports/lint-results-release.html` ;
- manifeste debug vérifié avec `aapt` : package `com.ethscalper.cockpit.debug`, versionCode 23300, versionName 2.33.0, minSdk 26, targetSdk 35.

## APK locale debug

- chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk` ;
- taille : `4 519 540 octets` ;
- SHA-256 : `7c4bb76a4c212d4ee1854b79a9cb22c3a96b643a7191686d46256fe8e410f764`.

## APK locale release

- chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\release\app-release-unsigned.apk` ;
- taille : `3 599 861 octets` ;
- SHA-256 : `808932cfba0b97f9aeafe38c3e45539c594c9e1a57e3be22562e396fc9235fd9`.

## GitHub Actions

Le workflow CI sera lancé sur l’unique commit applicatif final. L’ID du run, l’ID de l’artefact APK, sa taille et son SHA-256 seront reportés dans la description de la PR nº 2 et dans le compte rendu final, afin de ne pas créer un second commit documentaire.

## Corpus et avertissement

Le corpus d’analyse indiqué par le propriétaire couvre **14 sessions, 39 695 frames uniques et environ 77,14 heures**. Aucun replay/calibrage additionnel n’a été effectué par Codex. Les résultats d’analyse ne garantissent aucune rentabilité future.
