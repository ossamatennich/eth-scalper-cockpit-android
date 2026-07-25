# Candidate v2.32.7 — rapport d’implémentation

## Référence Git

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche source verrouillée : `agent/v2.32.6-candidate`
- HEAD source vérifié : `493820ef1d1a01a65160ffb56c91a8b04b255f62`
- Branche candidate créée : `agent/v2.32.7-scalp-p01-candidate`
- Cible Android : `versionCode 23270`, `versionName 2.32.7`
- Aucun merge vers `main` et aucune release définitive.

La première tentative de commit a été refusée car aucune identité Git locale n’était configurée dans ce clone neuf. L’identité déjà utilisée par l’historique du dépôt (`ossamatennich <ossamatennich@users.noreply.github.com>`) a été configurée uniquement dans ce dépôt, sans modifier la configuration Git globale.

## Fichiers modifiés

- `app/build.gradle`
- `app/src/main/java/com/ethscalper/cockpit/AiAdvisor.java`
- `app/src/main/java/com/ethscalper/cockpit/CandidateLifecycle.java` (nouveau au correctif d’audit)
- `app/src/main/java/com/ethscalper/cockpit/ConfirmedSizing.java` (nouveau au correctif de sizing)
- `app/src/main/java/com/ethscalper/cockpit/ConfirmedSignalPayload.java` (nouveau au correctif d’audit)
- `app/src/main/java/com/ethscalper/cockpit/ContinuationConfirmation.java` (nouveau)
- `app/src/main/java/com/ethscalper/cockpit/MainActivity.java`
- `app/src/main/java/com/ethscalper/cockpit/MarketSnapshot.java`
- `app/src/main/java/com/ethscalper/cockpit/MarketWatchService.java`
- `app/src/main/java/com/ethscalper/cockpit/SignalDecision.java`
- `app/src/main/java/com/ethscalper/cockpit/SignalEngine.java`
- `app/src/main/java/com/ethscalper/cockpit/SignalSafetyPolicies.java` (nouveau)
- `app/src/test/java/com/ethscalper/cockpit/SignalEngineTest.java`
- `app/src/test/java/com/ethscalper/cockpit/SignalEngineRulesTest.java`
- `app/src/test/java/com/ethscalper/cockpit/CandidateLifecycleIntegrationTest.java` (nouveau au correctif d’audit)
- `app/src/test/java/com/ethscalper/cockpit/ConfirmedSizingTest.java` (nouveau au correctif de sizing)
- `.github/workflows/build-apk.yml`
- `.github/workflows/build-v2327-candidate.yml` (nouveau)
- `.gitignore` (cache Gradle et produits de build)
- les quatre rapports de cette candidate.

## Corrections C01 à C08

- **C01 — feed stale** : le snapshot est construit et le lifecycle des candidats/risques est mis à jour avant le veto `V2326_ETH_FEED_STALE`. Le feed stale interdit une nouvelle entrée mais conserve TP, SL, invalidation et timeout. Texte public : « Nouvelles entrées bloquées — gestion des risques existants maintenue. »
- **C02 — exécution** : les diagnostics exposent `FAST_DEPARTURE`, `DELAYED_DEPARTURE`, `POST_TIMEOUT_DEPARTURE`, `LATE_RETURN_PARTIAL`, `LATE_RETURN_NEAR_TARGET`, `ENTRY_REVALIDATION_REJECTED`, `OPEN_ACTIVE_RISK` et `MISSED_NO_FILL` uniquement quand un non-fill est démontré.
- **C03** : le cahier des charges ne définit pas de nouvelle formule C03 autonome. Les veto de qualité, de mémoire de scénario et de revalidation présents sur la source verrouillée sont conservés ; aucun seuil non spécifié n’a été inventé.
- **C04** : veto symétrique au fill si `move1Aligned < avgRange20 × 0,08` et `flow30Aligned <= 0`, code `CONTINUATION_FRAICHEUR_PERDUE_AU_FILL`.
- **C05** : une RANGE_FADE remplie ne dépasse 15 minutes que si risque ≤ 0,45, progrès ≥ 0,35, `move3Aligned > 0` et `move8Aligned > -0,25 × avgRange20`. Code journalisé : `RANGE_FADE_TIMEOUT_RECOVERY_CONTEXT`. Le plafond de 45 minutes et les invalidations fortes restent prioritaires.
- **C06** : après 120 secondes, un signal final encore valide passe à `GÉRER`, action `GÉRER LE PLAN ACTIF`, code `V2326_ACTIVE_RISK_MANAGEMENT`.
- **C07** : veto symétrique au fill si les mouvements 1 min et 8 min sont opposés, code `CONTINUATION_CONFLIT_1M_8M_AU_FILL`.
- **C08** : veto si au moins 40 % de cible a déjà été parcouru avant fill, latence ≥ 120 s, et mouvements 1 min/3 min opposés. Code `CONTINUATION_MOUVEMENT_CONSOMME_AVANT_FILL`.

Les règles C04/C07/C08 ne changent jamais l’entrée, le TP, le SL ou la quantité.

## Correctifs obligatoires de l’audit externe

Le second commit de la même branche corrige les quatre blocages signalés, sans créer de branche supplémentaire :

1. **Veto replay CONTINUATION non bloquant** : `evaluateSignal()` ne passe plus les candidats CONTINUATION par `applyAiGate()`/`applyReplayRiskArbiter()` avant observation. `CandidateLifecycle.admit()` conserve `V232_REPLAY_RISK_VETO` et son détail dans les diagnostics comparatifs, mais laisse le plan brut atteindre la revalidation puis P01. Les veto replay RANGE_FADE restent bloquants.
2. **Confirmation immédiate** : `marketableAtCreation || entryTouched` déclenche immédiatement la reconstruction du snapshot et le parcours revalidation → C04/C07/C08 → P01. La constante historique de 15 secondes est conservée uniquement sous `legacyManualEntryDelayMs`, avec `legacyDelayApplied=false`.
3. **Résultats terminaux** : `TP_TOUCHED`, `SL_TOUCHED`, `SCENARIO_INVALIDATED`, `TIMEOUT_15M` et `TIMEOUT_45M` sont terminaux. Les journaux exposent `exitAt`, `exitPrice`, `exitReason`, résultat brut/frais/net réalisés, et mettent les champs latents à zéro. Seul `ACTIVE` avec entrée déclenchée peut devenir `OPEN_ACTIVE_RISK`.
4. **Tests du parcours** : `CandidateLifecycleIntegrationTest` utilise le même parcours de production que le service, depuis l’admission du plan brut et la revalidation jusqu’à P01, publication, politique sonore, quantité et résolution terminale.

La mémoire de scénario opposé ne considère désormais comme protection indispensable qu’un risque réellement `ACTIVE` dont l’entrée a été déclenchée ; une simple LIMIT silencieuse en attente n’écarte plus un candidat.

## P01 et contexte 15 minutes

Une CONTINUATION brute est désormais un candidat interne silencieux. Au toucher du niveau, un snapshot courant est utilisé. Le signal n’est publié que si :

- `move1Aligned >= avgRange20 × 0,40`
- `move3Aligned >= avgRange20 × 1,00`
- `flow30Aligned >= 0`
- feed ETH frais
- C04, C07 et C08 validées.

Le code final est `CONTINUATION_CONFLUENCE_POSITIVE_AU_FILL`. Le cooldown de 18 minutes est mémorisé uniquement à la confirmation P01 finale ; un candidat ou rejet ne le démarre pas. RANGE_FADE conserve son moteur et ses veto et ne passe pas par P01.

`move15` est calculé à partir des bougies 1 minute déjà présentes. Un mouvement 15 minutes aligné ajoute `P01_PREMIUM_15M` et l’affichage « Qualité premium 15 min » sans bloquer un P01 normal et sans notification supplémentaire.

## Quantité finale 3–7 ETH

Le troisième commit corrige la saturation du score moteur historique. `ConfirmedSizing.computeConfirmedSizingQuantity(...)` utilise uniquement les preuves observées au fill ; `candidate.score` est conservé sous `engineScoreDiagnosticOnly` et ne participe jamais au calcul.

Sizing CONTINUATION P01 :

- base conservatrice : 3 ETH
- +1 si `move1Aligned >= avgRange20 × 0,70` (minimum P01 : × 0,40)
- +1 si `move3Aligned >= avgRange20 × 1,50` (minimum P01 : × 1,00)
- +1 si `P01_PREMIUM_15M`
- +1 uniquement si le contexte complémentaire est particulièrement propre : `move8Aligned >= avgRange20 × 1,25`, `flow30Aligned >= 0,15`, `flow60Aligned >= 0,10`, ratio de volume ≥ 0,80 et `btcMove3Aligned >= -0,00010`
- plafond absolu : 7 ETH.

Un ancien veto replay CONTINUATION non bloquant impose un plafond à 5 ETH, même lorsque les quatre preuves positives seraient réunies. Il reste informatif et ne bloque pas P01.

RANGE_FADE ne bénéficie pas des bonus P01. Il reste à 3 ETH par défaut et ne peut atteindre 4 ETH que si le rebond au fill est particulièrement propre (`move1`, `move3`, `move8`, flow et volume alignés). Son plafond est 4 ETH tant qu’aucune calibration positive équivalente n’est disponible.

Le mapping score-only `SignalEngine.computeFinalConfirmedQuantity(int)` est conservé uniquement comme compatibilité de playback, marqué obsolète et n’est plus utilisé ni pour le candidat brut ni pour la publication finale. Un candidat brut porte 3 ETH en interne.

`ConfirmedSignalPayload` projette la quantité finale immuable vers l’écran, le recorder et la notification. Les diagnostics `confirmedSizing` enregistrent la famille, la base, les quatre bonus, les seuils et valeurs alignées, les points de preuve, le plafond applicable, le veto replay comparatif et la quantité finale.

## Notifications et expérience utilisateur

- Aucun candidat, fill en attente, veto, rejet, timeout de candidat ou analyse IA ne sonne.
- Même la fonction de test de notification est silencieuse afin de préserver la règle « un son = un signal final ».
- Un signal final utilise un titre `🚨 SIGNAL ETH CONFIRMÉ — LONG/SHORT` et le corps `LIMIT · TP · SL · quantité`, avec `PREMIUM 15M` si nécessaire.
- La signature SHA-256 logique contient side, famille, entrée, TP, SL et bucket temporel.
- Une signature déjà alertée est persistée et ne sonne pas une deuxième fois.
- Les changements de lifecycle réutilisent le même ID de notification sur un canal silencieux.
- Une invalidation affiche `SIGNAL EXPIRÉ — NE PAS ENTRER` dans la notification et en rouge dans l’application.
- Avant tout signal final, l’action principale est simplement « Analyse du marché en cours ».
- Aucun ordre n’est envoyé. `realTradingAllowed=false`.

## IA OpenAI

L’IA est hors chemin critique :

- aucun état `AI_PENDING`
- aucun veto ou délai IA avant publication
- aucune modification d’entrée, TP, SL ou quantité
- aucune notification IA
- aucun appel requis lorsque l’IA est désactivée.

Si l’utilisateur l’active, un avis asynchrone peut être enregistré après publication. Il ne modifie jamais le plan publié.

## Diagnostics et résultats

Les champs de fill ajoutés sont :

`marketableAtCreation`, `creationBid`, `creationAsk`, `creationLast`, `plannedEntry`, `firstEntryTouchAt`, `firstEntryTouchPrice`, `firstEntryTouchBid`, `firstEntryTouchAsk`, `simulatedFillAt`, `simulatedFillPrice`, `manualEntryConfirmed=false`, `manualEntryPrice`, `manualEntryAt`.

Les diagnostics de l’audit ajoutent aussi `replayRiskReasonCode`, `replayRiskDetail`, `replayRiskVetoBlocking`, `legacyManualEntryDelayMs`, `legacyDelayApplied`, `exitAt`, `exitPrice`, `exitReason` et l’objet détaillé `confirmedSizing`.

Les résultats distinguent `terminalResolved`, `realizedResult`, brut/frais/net réalisés, prix marqué, brut/net latent et âge du risque ouvert. Un risque encore actif n’est pas une perte réalisée ; une invalidation ou un timeout rempli est terminal, réalisé et jamais classé comme risque ouvert. Le coût de recherche/playback natif est unifié à `1,43 USDT/ETH` par aller-retour complet ; aucune configuration de frais réels utilisateur n’est modifiée.

## Limitations restantes

- Les 11 archives historiques demandées n’étaient présentes ni dans le workspace, ni dans `Téléchargements`, ni dans `Documents`. Aucun résultat de replay n’a été fabriqué.
- Les résultats de référence P01/candidate combinée n’ont donc pas été reproduits localement.
- Le replay exact n’a pas été exécuté avec le nouveau sizing fondé sur les preuves au fill. Aucun résultat historique P01 ou résultat financier n’est revendiqué pour cette politique de quantité.
- Le recorder compatible JSONL a été préservé sans refonte risquée ; voir `RECORDER_OPTIMIZATION_REPORT.md`.
- Les recherches historiques v2.33 et actifs anciens sont laissés intacts ; voir `REPO_CLEANUP_REPORT.md`.
- Les tests unitaires couvrent les politiques pures et leurs raccordements compilables. Un test instrumenté Android réel des canaux sonores reste pertinent avant promotion en release définitive.
