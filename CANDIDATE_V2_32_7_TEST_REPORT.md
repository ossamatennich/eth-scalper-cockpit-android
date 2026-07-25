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
gradle test
gradle test --rerun-tasks
gradle assembleDebug
gradle assembleDebug --rerun-tasks
```

Les commandes ont été exécutées avec le binaire Gradle 8.10.2 temporaire et les variables `JAVA_HOME`, `ANDROID_HOME` et `ANDROID_SDK_ROOT` positionnées pour le processus.

## Résultats

- 33 tests unitaires distincts.
- 33/33 réussis sur la variante debug.
- 33/33 réussis sur la variante release.
- 0 échec, 0 erreur, 0 test ignoré.
- Deux passes complètes réussies ; la seconde a utilisé `--rerun-tasks` pour vérifier le déterminisme au lieu d’accepter seulement l’état `UP-TO-DATE`.
- `assembleDebug` réussi, puis reconstruit avec `--rerun-tasks`.

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

## Incidents corrigés pendant la validation

1. `gradle` absent du `PATH` : utilisation autonome de Gradle 8.10.2 dans le dossier temporaire.
2. `Notification.Builder.setSilent` indisponible dans cette configuration : remplacement par un canal Android silencieux dédié aux mises à jour, avec le même ID de notification.
3. Ordre de classification `OPEN_ACTIVE_RISK`/`LATE_RETURN_*` : la priorité a été corrigée et le test a été relancé avec succès.

## APK

- Chemin exact : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk`
- Taille : `4 488 152` octets
- SHA-256 : `83789EA54F32E721AB526AA5B9DDF491EC9D21420FF915319B4C1A676C307BCC`

## Replay historique

Recherche effectuée dans le workspace, `C:\Users\Tenni\Downloads` et `C:\Users\Tenni\Documents`.

- Archives trouvées : `0/11`
- Replay historique : non exécuté
- Résultats P01/candidate combinée : non revendiqués et non fabriqués.
