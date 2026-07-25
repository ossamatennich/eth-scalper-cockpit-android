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

- 41 tests distincts, dont 8 tests d’intégration du parcours candidat.
- 41/41 réussis sur la variante debug.
- 41/41 réussis sur la variante release.
- 82 exécutions réussies au total sur la dernière passe complète.
- 0 échec, 0 erreur, 0 test ignoré.
- Deux passes complètes de 41 tests ont réussi pendant le correctif, plus une passe ciblée des 8 tests d’intégration.
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

## Incidents corrigés pendant la validation

1. `gradle` absent du `PATH` : utilisation autonome de Gradle 8.10.2 dans le dossier temporaire.
2. `Notification.Builder.setSilent` indisponible dans cette configuration : remplacement par un canal Android silencieux dédié aux mises à jour, avec le même ID de notification.
3. Ordre de classification `OPEN_ACTIVE_RISK`/`LATE_RETURN_*` : la priorité a été corrigée et le test a été relancé avec succès.
4. Pression mémoire Windows pendant la première relance d’audit : un daemon Gradle configuré à 2 Gio a échoué faute de mémoire native. Les daemons ont été arrêtés proprement, les tests unitaires limités à 128 Mio et les validations relancées en mono-worker avec une JVM Gradle à 384 Mio. Toutes les passes finales ont réussi.

## APK

- Chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk`
- Taille : `4 491 788` octets
- SHA-256 : `81C4046A08E0F922E87F24E88A8BCCD1F15E686F86741341F523DF758FCB262C`

## Replay historique

Recherche effectuée dans le workspace, `C:\Users\Tenni\Downloads` et `C:\Users\Tenni\Documents`.

- Archives trouvées : `0/11`
- Replay historique : non exécuté
- Résultats P01/candidate combinée : non revendiqués et non fabriqués.
