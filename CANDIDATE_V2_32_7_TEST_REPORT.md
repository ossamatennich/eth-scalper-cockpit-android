# Candidate v2.33.1 — rapport de tests

> Le nom historique du fichier est conservé. Tous les résultats concernent **ETH Scalper Cockpit v2.33.1 — Dual Sleeve Diagnostic Fix**.

## Environnement

- Windows / PowerShell
- Android SDK : `C:\Users\Tenni\AppData\Local\Android\Sdk`
- JDK : Android Studio JBR 17
- Gradle : 8.10.2, distribution officielle temporaire, le dépôt ne fournissant pas de wrapper fonctionnel
- `versionCode=23310`, `versionName=2.33.1`, `minSdk=26`, `targetSdk=35`

## Commandes exécutées

```powershell
gradle testDebugUnitTest --rerun-tasks
gradle test --rerun-tasks
gradle assembleDebug assembleRelease lintRelease --rerun-tasks
```

Les cinq tâches obligatoires `testDebugUnitTest`, `test`, `assembleDebug`, `assembleRelease` et `lintRelease` ont donc toutes été exécutées. Les processus utilisaient un seul worker avec `JAVA_HOME` et `ANDROID_HOME` limités à la session.

## Résultats unitaires

- 184 tests distincts : 175 tests existants inchangés et 9 nouveaux tests ciblés ;
- debug : 184/184 réussis ;
- release : 184/184 réussis ;
- passe globale : 368 exécutions réussies ;
- 0 échec, 0 erreur, 0 test ignoré.

La couverture ajoutée vérifie : setup non consommé pendant feed périmé, plan actif ou réarmement ; apparition unique au retour du feed frais et exactement à 180 000 ms ; `reset()` vers `NONE` ; OLS60 immédiat avec 60 bougies préchargées ; rejet si une minute manque ; exclusion des bougies futures et des closes zéro/NaN/infini ; symétrie LONG/SHORT. Les 175 assertions existantes restent intactes, notamment pour P01/P02, SL/TP, sizing, lifecycle TP/SL only, RANGE_FADE diagnostic et `realTradingAllowed=false`.

## Build et lint

- `assembleDebug` : succès ;
- `assembleRelease` : succès ;
- `lintRelease` : succès ;
- rapport lint : `app/build/reports/lint-results-release.html` ;
- manifeste debug vérifié avec `aapt` : package `com.ethscalper.cockpit.debug`, versionCode 23310, versionName 2.33.1, minSdk 26, targetSdk 35.
- lint release : 0 erreur ; les 54 avertissements existants ne bloquent pas la candidate.
- la première fenêtre d’exécution de la commande combinée a expiré côté outil ; la relance complète a continué jusqu’à la production des deux APK et des rapports lint valides, sans erreur de build.

## APK locale debug

- chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk` ;
- taille : `4 520 108 octets` ;
- SHA-256 : `bcc8646fb338201a0878975dc8c4bbab7d5d4dfdf42ca795b9f8c3ab4188c8cc`.

## APK locale release

- chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\release\app-release-unsigned.apk` ;
- taille : `3 600 621 octets` ;
- SHA-256 : `63295bfb2fab38ededdd72801205ecc5c7677c3d82e217c947ee0ec759e90bfc`.

## GitHub Actions

Les métadonnées finales CI ne sont pas anticipées dans ce commit. Le HEAD, l’ID du run, l’ID et le nom de l’artefact APK, sa taille et son SHA-256 sont placés dans la description de la PR nº 2 après le run, afin d’éviter un commit documentaire supplémentaire.

## Corpus et avertissement

Le corpus d’analyse indiqué par le propriétaire couvre **14 sessions, 39 695 frames uniques et environ 77,14 heures**. Aucun replay, calibrage, changement de formule ou changement de seuil n’a été effectué. Les résultats d’analyse ne garantissent aucune rentabilité future.
