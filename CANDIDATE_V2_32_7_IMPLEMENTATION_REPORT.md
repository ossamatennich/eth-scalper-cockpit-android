# Candidate v2.32.8.1 — rapport d’implémentation

> Le nom du fichier est conservé pour la continuité de la candidate, mais l’application produite est **ETH Scalper Cockpit v2.32.8.1 — TP/SL Only** (`versionCode 23281`, `versionName 2.32.8.1`).

## Référence Git

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche source verrouillée : `agent/v2.32.6-candidate`
- HEAD source : `493820ef1d1a01a65160ffb56c91a8b04b255f62`
- Branche candidate reprise : `agent/v2.32.7-scalp-p01-candidate`
- HEAD au départ de ce correctif : `ee29a0d08dfe9020c92b525c0bd226254b0e6628`
- Aucun merge vers `main`, aucune nouvelle branche et aucune release définitive.

## Portée du correctif v2.32.8.1

Fichiers modifiés pour ce correctif :

- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/ethscalper/cockpit/CandidateLifecycle.java`
- `app/src/main/java/com/ethscalper/cockpit/MainActivity.java`
- `app/src/main/java/com/ethscalper/cockpit/MarketWatchService.java`
- `app/src/main/java/com/ethscalper/cockpit/ActivePlanPersistence.java` (nouveau)
- `app/src/main/java/com/ethscalper/cockpit/ActivePlanState.java` (nouveau)
- `app/src/main/java/com/ethscalper/cockpit/SharedPreferencesActivePlanBackend.java` (nouveau)
- `app/src/main/java/com/ethscalper/cockpit/SignalEngine.java`
- `app/src/main/java/com/ethscalper/cockpit/SignalSafetyPolicies.java`
- `app/src/test/java/com/ethscalper/cockpit/ActivePlanPersistenceIntegrationTest.java` (nouveau)
- `.github/workflows/build-apk.yml`
- `.github/workflows/build-v2327-candidate.yml`
- les rapports de candidate.

Les seuils C01–C08, P01, premium 15 minutes, entrée, TP, SL, sizing confirmé 3–7 ETH, plafond replay à 5 ETH, plafond RANGE_FADE à 4 ETH, IA informative et interdiction du trading automatique ne sont pas modifiés.

## ACTIVE PLAN PERSISTENCE

- La confirmation finale est validée dans un état dédié versionné avant toute publication et avant l’alerte sonore.
- Le stockage repose sur des `SharedPreferences` internes dédiées et un `commit()` synchrone en une seule transaction ; le journal JSONL ne sert pas de source de restauration.
- L’état contient le plan, les timestamps, le statut `ACTIVE`, le premium 15 minutes, le score, le sizing et ses preuves, les dernières données de marché, `lastP01ConfirmedAt`, la signature et l’identifiant de notification.
- Au démarrage du service, la restauration précède la première évaluation : `lastSignal`, `lastSignalAt`, `lastP01ConfirmedAt` et l’`ObservedSignal` actif sont reconstruits.
- Le reason code `V23281_ACTIVE_PLAN_PERSISTED` confirme l’écriture et `V23281_ACTIVE_PLAN_RESTORED` la reprise.
- Une restauration remet silencieusement à jour le même identifiant de notification avec `PLAN ACTIF RESTAURÉ`; elle ne sonne pas, ne vibre pas et ne publie aucun nouveau signal.
- Le verrou d’un seul plan reste actif après redémarrage et bloque LONG, SHORT, P01 et RANGE_FADE jusqu’au TP ou au SL.
- Seuls `TP_TOUCHED` et `SL_TOUCHED` suppriment automatiquement l’état persistant. Le temps, le contexte, le feed stale, une déconnexion ou une invalidation analytique ne le suppriment pas.
- `ACTION_RESET_DIAGNOSTICS` conserve le plan, ses timestamps, son objet actif, le cooldown P01 et son état persistant ; seuls les diagnostics et candidats non actifs sont effacés.
- Un état incomplet ou corrompu est rejeté sans plan synthétique ni crash avec `V23281_ACTIVE_PLAN_RESTORE_INVALID`, puis l’analyse normale continue.
- Le petit correctif Android 26–28 ajouté pendant le lint garde l’export diagnostic fonctionnel sans appeler l’API 29 `MediaStore.Downloads` sur ces versions ; il ne touche pas au moteur.

## TP/SL ONLY LIFECYCLE

- Après publication finale, le statut live reste `ACTIVE` sans limite d’âge.
- Il n’existe plus de sortie live à 15 minutes ou 45 minutes et aucune extension de timeout n’est nécessaire.
- `SCENARIO_INVALIDATED`, un changement de flow, BTC, momentum ou une faiblesse de contexte ne ferment jamais un plan final.
- Seuls `TP_TOUCHED` et `SL_TOUCHED` sont terminaux dans le parcours live et déclenchent le calcul du résultat réalisé.
- Tant que ni TP ni SL n’est touché, le résultat reste latent, la décision publique est `GÉRER` et l’action est `GÉRER LE PLAN ACTIF`.
- L’écran montre `PLAN ACTIF`, puis uniquement `TP ATTEINT — PLAN TERMINÉ` ou `SL ATTEINT — PLAN TERMINÉ`.
- Les anciens statuts `SCENARIO_INVALIDATED`, `TIMEOUT_15M` et `TIMEOUT_45M` restent reconnus par `isHistoricalTerminalStatus()` pour la compatibilité des playbacks, mais ne sont plus produits par le parcours actif.
- Le feed stale bloque toujours la création d’un nouveau signal, mais `updateObservedSignals()` continue avant ce veto et surveille TP/SL sans timeout.

## Un seul plan final actif

Avant toute confirmation, `MarketWatchService` recherche un autre objet ayant `status=ACTIVE`, une entrée déclenchée et un `finalConfirmedAt` valide. Dans ce cas :

- aucun CONTINUATION P01, SHORT, LONG ou RANGE_FADE supplémentaire n’est publié ;
- aucune deuxième notification sonore n’est possible ;
- le candidat reste interne et silencieux ;
- le diagnostic enregistre une seule fois `V2328_ACTIVE_SIGNAL_ALREADY_RUNNING` avec le sens « Nouveau candidat ignoré : un plan final est déjà actif jusqu’au TP ou au SL. » ;
- après `TP_TOUCHED` ou `SL_TOUCHED`, le verrou est automatiquement levé.

Le nettoyage du journal ne retire jamais l’objet du plan final actif, même si de nombreux candidats silencieux sont enregistrés.

## Déduplication des candidats

`PendingCandidateIndex` indexe les candidats `LIMIT_PENDING` avec une signature SHA-256 déterministe comprenant exactement le side, la famille, l’entrée, le TP et le SL, sans bucket temporel.

Pour une signature déjà en attente :

- aucun nouvel objet ni événement `CREATED` n’est produit ;
- le premier objet, son `createdAt` et sa première observation sont conservés ;
- le marché courant, les extrêmes et le compteur d’updates sont mis à jour ;
- `V2328_EXISTING_CANDIDATE_UPDATED` est journalisé ;
- le même candidat est immédiatement revalidé lorsqu’il est marketable ou touché ;
- un refus P01 transitoire conserve le candidat `LIMIT_PENDING`, afin que C04/C07/C08/P01 puissent être réévalués sans recréer 27 objets ;
- une seule confirmation et une seule notification finale sont possibles.

Les diagnostics ajoutent `candidateSignature` et `activeSignalPublicationBlocked` sans casser les champs historiques.

## Notifications et affichage

- Un son signifie toujours et uniquement qu’un nouveau signal final est confirmé.
- Candidat, doublon, blocage par plan actif, contexte affaibli, timeout historique, invalidation analytique, IA et mise à jour ne sonnent jamais.
- TP ou SL met à jour silencieusement la notification existante avec le même ID.
- Aucun message public de sortie, d’expiration ou d’invalidation analytique n’est affiché après publication.
- La quantité reste issue de `ConfirmedSizing` et identique dans le plan, l’écran, la notification et le diagnostic.
- `realTradingAllowed=false` reste inchangé : l’application n’envoie aucun ordre.

## IA et règles de signal

L’IA reste strictement asynchrone et informative après publication. Elle ne peut modifier ni fermer le plan, ni changer l’entrée, le TP, le SL ou la quantité, ni notifier.

C01–C08 et P01 restent appliqués avant la publication finale. C05 est conservée dans le laboratoire et les anciens rapports, mais ne provoque plus de timeout ou sortie live. RANGE_FADE reste hors P01 et conserve ses veto et son sizing conservateur.

## Limitations

- Aucun replay historique exact n’a été relancé pour ce changement de lifecycle ; aucun résultat financier historique n’est revendiqué.
- Les formats historiques de timeout/invalidation restent lisibles en laboratoire, sans influencer le parcours live v2.32.8.1.
- Les tests JVM valident les politiques et composants de production ; un essai instrumenté sur appareil réel reste recommandé avant toute promotion hors brouillon.
