# Candidate v2.32.8 — rapport de tests

> Le nom du fichier reste historique ; tous les résultats ci-dessous concernent **ETH Scalper Cockpit v2.32.8 — TP/SL Only**.

## Environnement

- Windows / PowerShell
- Android SDK : `C:\Users\Tenni\AppData\Local\Android\Sdk`
- JDK : Android Studio JBR 17
- Gradle : 8.10.2 temporaire, le dépôt ne fournissant pas de wrapper fonctionnel
- Package debug vérifié par `aapt` : `com.ethscalper.cockpit.debug`
- `versionCode=23280`, `versionName=2.32.8`, `minSdk=26`, `targetSdk=35`

## Commandes exécutées

```powershell
gradle testDebugUnitTest --rerun-tasks
gradle testReleaseUnitTest --rerun-tasks
gradle test --rerun-tasks
gradle assembleDebug --rerun-tasks
gradle assembleRelease --rerun-tasks
```

Les commandes ont été exécutées en mono-worker avec `JAVA_HOME` et `ANDROID_HOME` limités au processus.

## Résultats

- 70 tests distincts.
- 70/70 réussis en debug.
- 70/70 réussis en release.
- 140 exécutions réussies pendant la dernière passe globale forcée.
- 0 échec, 0 erreur, 0 test ignoré.
- `assembleDebug --rerun-tasks` : succès.
- `assembleRelease --rerun-tasks` et lint vital release : succès.

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
- Taille : `4 495 440` octets
- SHA-256 : `146C522E8CBA8D32F83A5B32ACF61DEBCB73600006AB18D4DE4839E0B27295E3`
- Manifeste vérifié : `versionCode 23280`, `versionName 2.32.8`.

Contrôle release local non signé :

- Chemin : `app\build\outputs\apk\release\app-release-unsigned.apk`
- Taille : `3 579 485` octets
- SHA-256 : `99BB2570AC6EB574C0B1850D19A020A3F63949452091628A14C8D8E07DB70203`

## GitHub Actions

Le workflow candidat est configuré pour publier `ETH-Scalper-Cockpit-v2.32.8-debug-apk`. Le run, l’artefact et son SHA-256 seront ajoutés après le push du commit testé, sans transformer la PR en PR prête et sans créer de release.

## Incidents environnementaux corrigés

Le premier packaging local a échoué après compilation et dex parce qu’un renderer Chrome défaillant retenait environ 18,9 Gio de mémoire privée, épuisant le fichier d’échange Windows. Seul ce renderer a été arrêté ; la session Chrome principale est restée ouverte. La mémoire virtuelle libérée a permis de relancer exactement `assembleDebug --rerun-tasks` avec succès. La première tentative release a ensuite atteint une limite locale de metaspace au lint ; la commande a été relancée avec davantage de metaspace et a réussi.

## Replay historique

Aucun replay historique exact n’a été exécuté pour le lifecycle v2.32.8. Aucun résultat historique P01, sizing ou financier n’est revendiqué ou fabriqué.
