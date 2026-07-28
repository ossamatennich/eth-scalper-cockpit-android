# Candidate v2.34.0.3 — Implementation report

## Référence et portée

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche : `agent/v2.32.7-scalp-p01-candidate`
- HEAD de départ verrouillé : `14c89bc0dbe97ff9754f5120eac37280769f1495`
- Base PR : `agent/v2.32.6-candidate`
- Version : `23403` / `2.34.0.3`
- Nom : **ETH + SOL Scalper Cockpit v2.34.0.3 — Stable Multi-Market Research**
- Mode : `RESEARCH_ONLY`, `realTradingAllowed=false`, exécution manuelle uniquement.

## Routage extensible

`MarketDataRouter` résout chaque symbole tradable depuis `MarketRegistry` et route de façon générique bookTicker, kline, aggTrade, préchargement REST et fallback REST vers son `MarketRuntime`. BTC conserve une voie distincte parce qu'il est le contexte partagé non tradable. Le runtime ETH synchronise aussi le miroir historique ETH afin de préserver son pipeline et ses résultats.

`MarketWatchService` ne contient plus de branche SOL dédiée dans les handlers, le préchargement ou les fallbacks. Le test avec un troisième profil prouve que le service route quotes, bougies et trades sans nouvelle condition propre au symbole.

## Interface extensible

`MainActivity` construit les cartes des marchés depuis `MarketUiCatalog` et le registre. Aucun champ `solPrice`, `solQuotes` ou `solSignalValue` ne subsiste. Chaque carte utilise les mêmes vues dynamiques pour symbole, actif, prix, bid/ask, fraîcheur, état, plan, quantité, LIMIT, TP, SL, qualité et risque. BTC reste une carte de contexte distincte.

## Admission SOL complète

`MarketAdmissionPolicy` applique avant admission les protections structurelles partagées : validité du candidat et du plan, fraîcheur du marché et de BTC, verrou mono-plan par symbole, réarmement, mémoire opposée isolée, déduplication, tombstones, mouvement consommé et conflits momentum/flow. Les règles sont classées `STRUCTURAL_SHARED` ou `ETH_HISTORICAL_ONLY`.

Le modèle historique replay ETH n'est pas présenté comme un veto non-ETH : son absence produit le diagnostic comparatif générique `V23402_MARKET_HISTORICAL_REPLAY_MODEL_UNAVAILABLE`, avec symbole, profil et classification `ETH_HISTORICAL_ONLY`. Ce diagnostic n'est jamais bloquant. Le chemin ETH historique demeure inchangé.

## Recorder multi-marchés complet

Chaque `MarketRuntime` possède un `MarketDiagnosticRecorder` borné et indépendant. Les événements lifecycle sont exclusivement conservés dans `events`; `MARKET_FRAME` est exclusivement conservé dans la deque `frames`. Chaque ligne porte le symbole, l'actif, la version du profil et les métriques de marché/sleeve/risque. Seul `PLAN_CONFIRMED` compte comme trade ; `PLAN_RESTORED` alimente le compteur séparé `restoredActivePlans`. Un candidat pending, rejeté ou réinséré ne compte jamais comme trade.

Les événements et frames sont persistés séparément dans `persistent_market_events.jsonl` et `persistent_market_frames.jsonl`. Les frames sont échantillonnées au maximum une fois toutes les cinq secondes par symbole. Chaque fichier courant est limité à 64 Mio et conserve au plus une génération `.1`; l'export relit `.1` puis le courant. Le reset efface les deux générations tout en conservant et réinsérant chaque plan actif.

## Status Android borné et export ZIP v2.34.0.3

Le status Android ne transporte plus les collections longues `marketDiagnostics`, `marketCandidates`, `marketPlanHistory`, `multiMarketFrames` ou `observedSignals`. Il conserve les états courants, plans, risques, résumés et au plus 20 diagnostics récents. Le test simulé de deux heures ETH+SOL mesure 18 917 octets.

Le ZIP `ETH_SOL_Scalper_Diagnostic_v2_34_0_3_<date>.zip` reconstruit diagnostics, candidats, plans, frames et résumés à partir des deux journaux JSONL persistants, y compris leur génération `.1`. Les exports historiques ETH complémentaires restent disponibles sous des noms `legacy_eth_*`.

## Timestamp P01

`MarketPlanOrchestrator` ne met `lastP01ConfirmedAt` à jour que pour un sleeve réellement P01. Une confirmation P02 conserve la valeur antérieure, y compris dans l'état persistant.

## Golden master ETH indépendant

La référence a été produite dans un worktree temporaire détaché au commit historique exact `5e00f3f88bf2da5237ae7f8c0d851aa0fb4fe251`, avec la graine `23321042`, sans modifier cet ancien commit. Elle couvre 20 000 snapshots SignalEngine, décisions, reason codes/textes, scores, quantités, entrée/TP/SL, métriques normalisées, P01 normal/anticipé, frontières 999/1 000 et 14 999/15 000 ms, P02, OLS60, tous les champs de `DynamicTradePlan`, signatures, terminaux TP/SL, réarmement et restauration ETH.

- Manifest attendu : `app/src/test/resources/eth_v23321_golden_manifest.properties`
- SHA-256 du fichier : `cc443c78d8e1b6ff71920b57edb0cdddf329a83919a77957aca7adbbaee503bb`
- Digest global attendu : `dd17b73ee7748179cac67f3b05592b4d53ce96e24f3766763054179c9a56b8d3`

Le test courant charge ces valeurs immuables ; il ne calcule pas son expected avec une seconde méthode de la candidate.

## Validateur SOL durci

`validate_quantity` rejette désormais réellement toute quantité hors 1..120. Aucun `min(q, 120)` ne subsiste. Le scan intégral comptabilise les plans explicitement rejetés afin de poursuivre les statistiques sans réduire silencieusement leur quantité. Le manifest officiel est versionné dans `SOL_PROFILE_V1_CORPUS_MANIFEST.json`.

## Lifecycle et sécurité inchangés

- Un plan ETH et un plan SOL peuvent être actifs simultanément ; un seul plan par symbole.
- Un terminal d'un symbole ne clôture, ne réarme et n'efface que ce symbole.
- Après publication, seule une touche TP ou SL termine un plan.
- Aucun timeout, trailing, break-even ou veto analytique post-publication.
- RANGE_FADE reste diagnostic-only.
- BTC reste contexte uniquement et ne publie aucun plan.
- Aucun ordre automatique, aucune clé exchange, aucune API privée.

La candidate reste un logiciel de recherche. Aucun résultat historique ou contrôle de cohérence ne constitue une garantie de performance future.
