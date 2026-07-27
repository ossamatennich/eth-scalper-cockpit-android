# Candidate v2.33.2 — rapport de tests

> Le nom historique du fichier est conservé. Tous les résultats concernent **ETH Scalper Cockpit v2.33.2 — Timely P01 + Quantity Uplift**.

## Environnement

- Windows / PowerShell ; Android SDK `C:\Users\Tenni\AppData\Local\Android\Sdk`.
- Android Studio JBR 17 ; Gradle 8.10.2.
- `versionCode=23320`, `versionName=2.33.2`, `minSdk=26`, `targetSdk=35`.

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

- 204 tests distincts : les 184 tests v2.33.1 sont conservés, avec 20 tests supplémentaires ou nouvelles assertions ciblées v2.33.2.
- passe debug forcée : 204/204 réussis ;
- passe globale : debug 204/204 et release 204/204 réussis ;
- 408 exécutions dans la matrice debug/release, et 612 exécutions locales au total en comptant la passe debug forcée séparée ;
- 0 échec, 0 erreur, 0 test ignoré.

La couverture vérifie les deux voies P01, les frontières à ±`1e-9`, les cas connus `m8`, les resets de stabilité, 999/1 000 ms, l’âge 15 000 ms, feed/LIMIT/snapshot/plan/réarmement, symétrie LONG/SHORT, publication du même plan, mapping complet de quantité, budgets 10.00/14.55, perte maximale, rejet sans réduction, invariance entrée/TP/SL, P02 inchangé, lifecycle TP/SL only, RANGE_FADE diagnostic-only et `realTradingAllowed=false`.

Les six sessions nommées et le cas structurel complémentaire sont représentés par des fixtures déterministes issues des valeurs fournies. Les archives de replay exactes étant absentes localement, aucun résultat historique supplémentaire n’est revendiqué.

## Builds et lint

- `assembleDebug` : succès ;
- `assembleRelease` : succès ;
- `lintRelease` : succès, 0 erreur et 54 avertissements non bloquants existants ;
- rapport : `app/build/reports/lint-results-release.html`.

## APK locale debug

- chemin : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk` ;
- taille : `4 524 624 octets` ;
- SHA-256 : `4da62af85d824647e2b8f5ba204ef8aafd4031ec4747e63deba104427b105ccb`.

## APK locale release

- chemin : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\release\app-release-unsigned.apk` ;
- taille : `3 604 577 octets` ;
- SHA-256 : `0ecdeb3dba389c52492fc00f81dfae7a50d81378de917a4dd2641a143dcf99e7`.

## GitHub Actions

Les métadonnées finales du run et de l’artefact CI sont inscrites dans la description de la PR nº 2 après vérification du workflow, afin que le commit applicatif et documentaire reste unique.

## Avertissement

Les montants de replay et résultats contrefactuels fournis servent uniquement de contrôles de recherche. Ils ne constituent aucune promesse ni garantie de performance financière future.
