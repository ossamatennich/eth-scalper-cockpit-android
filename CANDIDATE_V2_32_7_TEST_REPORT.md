# Candidate v2.32.8.1 — rapport de tests

> Le nom du fichier reste historique ; tous les résultats ci-dessous concernent **ETH Scalper Cockpit v2.32.8.1 — TP/SL Only**.

## Environnement

- Windows / PowerShell
- Android SDK : `C:\Users\Tenni\AppData\Local\Android\Sdk`
- JDK : Android Studio JBR 17
- Gradle : 8.10.2 temporaire, le dépôt ne fournissant pas de wrapper fonctionnel
- Package debug vérifié par `aapt` : `com.ethscalper.cockpit.debug`
- `versionCode=23281`, `versionName=2.32.8.1`, `minSdk=26`, `targetSdk=35`

## Commandes exécutées

```powershell
gradle test --rerun-tasks
gradle assembleDebug --rerun-tasks
gradle assembleRelease --rerun-tasks
gradle lintRelease --rerun-tasks
```

Les commandes ont été exécutées en mono-worker avec `JAVA_HOME` et `ANDROID_HOME` limités au processus.

## Résultats

- 88 tests distincts.
- 88/88 réussis en debug.
- 88/88 réussis en release.
- 176 exécutions réussies pendant la dernière passe globale forcée.
- 0 échec, 0 erreur, 0 test ignoré.
- `assembleDebug --rerun-tasks` : succès.
- `assembleRelease --rerun-tasks` et lint vital release : succès.
- `lintRelease --rerun-tasks` : succès après ajout de la garde API 29 dans l’export diagnostic.

## ACTIVE PLAN PERSISTENCE

Les 18 nouveaux tests couvrent :

1. écriture du plan après confirmation ;
2. restauration dans une nouvelle instance de persistance/service ;
3. blocage d’un nouveau LONG par le plan restauré ;
4. blocage d’un nouveau SHORT ;
5. blocage d’un RANGE_FADE ;
6. restauration silencieuse ;
7. conservation du même identifiant de notification ;
8. maintien actif après 15 minutes ;
9. maintien actif après 45 minutes ;
10. maintien malgré un contexte défavorable ;
11. suppression de l’état persistant par TP ;
12. suppression par SL ;
13. nouveau signal autorisé après TP ;
14. nouveau signal autorisé après SL ;
15. reset diagnostic conservant le plan actif ;
16. reset normal sans plan actif ;
17. rejet sans crash d’un état corrompu ;
18. `realTradingAllowed=false` et aucun ordre automatique.

La restauration vérifie aussi l’identité de la quantité et des preuves de sizing. Aucun seuil C01–C08/P01, TP, SL ou sizing n’a été modifié.

## TP/SL ONLY LIFECYCLE

Les tests couvrent explicitement :

1. blocage d’un nouveau P01 LONG par un plan final actif ;
2. blocage d’un nouveau P01 SHORT ;
3. blocage d’un nouveau RANGE_FADE ;
4. silence du candidat bloqué ;
5. 27 observations identiques produisant un seul objet candidat ;
6. conservation du premier `createdAt` ;
7. refus P01 transitoire puis confirmation du même candidat ;
8. une seule alerte sonore finale ;
9. maintien `ACTIVE` après 15 minutes ;
10. maintien `ACTIVE` après 45 minutes ;
11. maintien `ACTIVE` lorsque flow/BTC/contexte deviennent défavorables ;
12. `SCENARIO_INVALIDATED` non terminal dans le parcours live ;
13. aucune action publique `SORTIR` ;
14. aucune action publique d’expiration ;
15. TP terminal et réalisé ;
16. SL terminal et réalisé ;
17. nouveau signal autorisé après TP ;
18. nouveau signal autorisé après SL ;
19. quantité identique plan/notification/écran/diagnostic ;
20. `realTradingAllowed=false` et aucun ordre automatique.

Les suites antérieures restent vertes pour C01–C08, P01 LONG/SHORT, premium 15 minutes, cooldown, RANGE_FADE hors P01, veto replay comparatif, confirmation immédiate marketable, sizing confirmé 3–7 ETH, plafond replay 5 ETH, plafond RANGE_FADE 4 ETH, immutabilité IA, fill et diagnostics.

Les anciens tests qui réalisaient un timeout ou une invalidation ont été adaptés : ces codes restent historiques, sans `exitAt`, `exitPrice`, frais ou résultat réalisé live. Seuls TP et SL résolvent un plan final.

## APK locale

- Chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk`
- Taille : `4 504 264` octets
- SHA-256 : `123D9DB060B0175FE354D2CB8C47F323DAABE53C5C1F1F383FA258D719830FD1`
- Manifeste vérifié par `aapt` : `versionCode 23281`, `versionName 2.32.8.1`, `minSdk 26`, `targetSdk 35`.

Contrôle release local non signé :

- Chemin : `app\build\outputs\apk\release\app-release-unsigned.apk`
- Taille : `3 585 505` octets
- SHA-256 : `970BE00D74B99169CB9CC2701D8EFFF95AE4373D153331030D04D0B34B13A70A`

## GitHub Actions

Run du commit applicatif `04d224fb2b8c58208de631345b8251e4f29823d5` :

- Run : `30175973884`
- URL : `https://github.com/ossamatennich/eth-scalper-cockpit-android/actions/runs/30175973884`
- Job `test-and-build` : succès (tests, build debug et upload).
- Artefact : `ETH-Scalper-Cockpit-v2.32.8.1-debug-apk`
- ID artefact : `8624218470`
- Taille de l’archive GitHub : `4 044 257` octets
- Digest de l’archive GitHub : `sha256:e5e749dc4ebb1787a20e29e51fe442886b491a5965880b77b069d9b0219d1e65`
- Taille de l’APK extraite : `4 503 472` octets
- SHA-256 de l’APK GitHub Actions : `69E398BFB9D00A0850409E835EBA4AF8A3D69D010C08908C5CA6B456C81D1D68`
- Manifeste de l’APK CI vérifié par `aapt` : `versionCode 23281`, `versionName 2.32.8.1`, `minSdk 26`, `targetSdk 35`.

La différence de hash avec l’APK locale provient des builds/signatures debug produits dans deux environnements distincts ; les deux manifestes portent la même version. La PR reste en brouillon et aucun artefact de release définitive n’a été publié.

## Incidents environnementaux corrigés

Une première passe globale a rencontré un verrou Windows transitoire sur un `R.jar` release généré. `gradle clean` a libéré uniquement les intermédiaires régénérables, puis `test --rerun-tasks` a réussi. Le lint complet a ensuite signalé l’usage non gardé de `MediaStore.Downloads` (API 29) dans l’export diagnostic pour un `minSdk` 26 ; une branche compatible Android 26–28 a été ajoutée et le lint complet a réussi à la relance.

## Replay historique

Aucun replay historique exact n’a été exécuté pour la persistance v2.32.8.1. Aucun résultat historique P01, sizing ou financier n’est revendiqué ou fabriqué.
