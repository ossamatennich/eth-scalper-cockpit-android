# Candidate v2.33.2.1 — rapport de tests

> Le nom historique du fichier est conservé. Tous les résultats concernent **ETH Scalper Cockpit v2.33.2.1 — All-Sleeve Quantity Uplift**.

## Environnement

- Windows / PowerShell ; Android SDK `C:\Users\Tenni\AppData\Local\Android\Sdk`.
- Android Studio JBR 17 ; Gradle 8.10.2.
- `versionCode=23321`, `versionName=2.33.2.1`, `minSdk=26`, `targetSdk=35`.

## Commandes obligatoires exécutées

```powershell
gradle testDebugUnitTest --rerun-tasks
gradle test --rerun-tasks
gradle assembleDebug
gradle assembleRelease
gradle lintRelease
```

Toutes les commandes sont réussies.

## Résultats unitaires

- 210 tests distincts : les 204 tests v2.33.2 sont conservés et 6 tests ciblés sont ajoutés ;
- passe debug forcée : 210/210 réussis ;
- passe globale : debug 210/210 et release 210/210 réussis ;
- 420 exécutions dans la matrice debug/release, 630 exécutions officielles en comptant la passe debug forcée ;
- 0 échec, 0 erreur, 0 test ignoré.

La couverture ajoutée vérifie : P02 baseline `2→3`, baseline `3→4`, mapping `6→7` et `7→7`, plage publique 3–7, invariance entrée/TP/SL, contrôle naturel `-5,38→-8,07` uniquement par quantité, fenêtres P02 20–45 secondes, stabilité P01 1 000 ms, égalité de quantité dans les surfaces publiques et restauration persistante. Les tests existants continuent de couvrir les filtres P02, OLS60, P01 anticipé, réarmement, RANGE_FADE diagnostic-only, TP/SL only et `realTradingAllowed=false`.

## Builds et lint

- `assembleDebug` : succès ;
- `assembleRelease` : succès ;
- `lintRelease` : succès, 0 erreur et 54 avertissements non bloquants existants ;
- rapport : `app/build/reports/lint-results-release.html`.

## APK locale debug

- chemin : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk` ;
- taille : `4 524 512 octets` ;
- SHA-256 : `e02fa06af26df0015cef19758c810627b872507cb843bb382219380bbaf82bc8`.

## APK locale release

- chemin : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\release\app-release-unsigned.apk` ;
- taille : `3 604 557 octets` ;
- SHA-256 : `0f4e620be7e3b58efaa1513a080259bb66bd7de16c140efe48016bfb68103c0d`.

## GitHub Actions

Les métadonnées finales du run et de l’artefact CI sont inscrites dans la description de la PR nº 2 après vérification du workflow, afin de conserver un commit unique.

Les résultats historiques et contrefactuels servent uniquement de contrôles de recherche et ne garantissent aucune performance financière future.
