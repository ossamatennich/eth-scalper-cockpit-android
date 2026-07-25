# Candidate v2.32.7 — rapport de tests

## Environnement

- Windows / PowerShell
- Android SDK : `C:\Users\Tenni\AppData\Local\Android\Sdk`
- JDK : Android Studio JBR 17
- Gradle : 8.10.2
- Package debug vérifié avec `aapt` : `com.ethscalper.cockpit.debug`
- `versionCode=23270`, `versionName=2.32.7`, `minSdk=26`, `targetSdk=35`

Le dépôt ne contenait pas de wrapper fonctionnel et `gradle` n’était pas installé dans le `PATH`. Gradle 8.10.2 a été téléchargé dans le dossier temporaire utilisateur, sans ajout au dépôt.

## Commandes exécutées

Équivalents locaux des commandes demandées :

```powershell
gradle --no-daemon --max-workers=1 test --rerun-tasks
gradle --no-daemon --max-workers=1 assembleDebug --rerun-tasks
```

Les commandes ont été exécutées avec le binaire Gradle 8.10.2 temporaire, une JVM locale plafonnée à 384 Mio et les variables `JAVA_HOME`/`ANDROID_HOME` positionnées pour le processus.

## Résultats

- 50 tests distincts : les 41 tests précédents plus 9 tests dédiés au sizing confirmé.
- 50/50 réussis sur la variante debug.
- 50/50 réussis sur la variante release.
- 100 exécutions réussies au total sur la dernière passe complète.
- 0 échec, 0 erreur, 0 test ignoré.
- Une passe ciblée des 9 nouveaux tests de sizing a réussi avant la passe complète.
- La dernière passe complète et `assembleDebug` ont utilisé `--rerun-tasks`.

Couverture principale :

- C01, C04 LONG/SHORT, C07 LONG/SHORT, C08
- P01 LONG/SHORT et refus move1/move3/flow/feed stale
- premium 15 min non bloquant
- cooldown après confirmation seulement
- RANGE_FADE hors P01
- C05 et plafond absolu 45 minutes
- politique sonore candidat/final/doublon/invalidation
- quantités 3, 4, 5, 6, 7
- immutabilité IA du plan
- fill marketable LONG/SHORT
- classifications d’exécution
- séparation résultat réalisé/latent
- action de gestion après 120 secondes
- trading réel désactivé.
- CONTINUATION historiquement marquée `V232_REPLAY_RISK_VETO` admise jusqu’à P01
- publication finale après revalidation + C04/C07/C08/P01
- `marketableAtCreation` confirmé au même instant, sans délai historique
- silence avant publication et un seul son par signature finale
- invalidation/timeout remplis terminaux, réalisés et jamais `OPEN_ACTIVE_RISK`
- quantité identique plan/notification/écran/diagnostic
- RANGE_FADE toujours protégé par ses veto replay et hors P01.
- quantités confirmées 3, 4, 5, 6 et 7 produites par des preuves au fill représentatives
- score moteur 96 ne donnant pas automatiquement 7 ETH
- ancien veto replay plafonnant à 5 ETH un contexte autrement dimensionné à 7 ETH
- RANGE_FADE score 96 restant conservateur à 3 ETH et plafonné à 4 ETH
- valeurs, seuils, bonus et plafonds du sizing exposés dans `confirmedSizing`.

## Incidents corrigés pendant la validation

1. `gradle` absent du `PATH` : utilisation autonome de Gradle 8.10.2 dans le dossier temporaire.
2. `Notification.Builder.setSilent` indisponible dans cette configuration : remplacement par un canal Android silencieux dédié aux mises à jour, avec le même ID de notification.
3. Ordre de classification `OPEN_ACTIVE_RISK`/`LATE_RETURN_*` : la priorité a été corrigée et le test a été relancé avec succès.
4. Pression mémoire Windows pendant la première relance d’audit : un daemon Gradle configuré à 2 Gio a échoué faute de mémoire native. Les daemons ont été arrêtés proprement, les tests unitaires limités à 128 Mio et les validations relancées en mono-worker avec une JVM Gradle à 384 Mio. Toutes les passes finales ont réussi.

## APK

- Chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk`
- Taille : `4 494 016` octets
- SHA-256 : `D320212755A624C87D3255AD327ABAB8A66B7FD2FE717DEE86D345F6357D623D`

## Replay historique

Recherche effectuée dans le workspace, `C:\Users\Tenni\Downloads` et `C:\Users\Tenni\Documents`.

- Archives trouvées : `0/11`
- Replay historique : non exécuté
- Résultats P01/candidate combinée : non revendiqués et non fabriqués.
- Nouveau sizing 3–7 : aucun résultat historique ni résultat financier revendiqué avant un replay exact.
