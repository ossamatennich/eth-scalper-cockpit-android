# NMC v2.34.4.3 — Quality/Frequency Shadow Report

## Portée

Cette candidate construit l’architecture d’observation shadow qualité + fréquence. Elle s’appuie sur le contexte de recherche fourni pour dix diagnostics totalisant environ 66,7 heures, sans prétendre les avoir rejoués ici puisqu’ils ne sont pas joints.

Le moteur public ETH legacy et le moteur public SOL restent inchangés. Aucun filtre, timing, score, plan, TP, SL, quantité, alerte, persistance ou réarmement public n’est modifié. `realTradingAllowed=false` et l’exécution demeure manuelle.

## Politique SHADOW_V23443_20260802 / SHADOW_SCHEMA_V4

- garde P01 symbolique : strict pour ETH, baseline KEEP pour SOL ;
- `SOL_P01_EARLY_RESUMPTION`, fondée sur `CandidateLifecycle.processEarlyP01Candidate` et `P01EarlyConfirmation` ;
- `ETH_FLOW_CONTINUATION_HIGH_CONFIDENCE`, ré-ancrée sur la cotation exécutable courante ;
- `ETH_RANGE_FADE_LONG_HIGH_CONFIDENCE`, secondaire et uniquement LONG ;
- aucun quota de signaux ; géométrie valide et rendement/risque net minimum 0,40 obligatoires ;
- déduplication indépendante par composant, clé de mouvement bornée et overlaps explicites ;
- résumé incrémental exportable des opportunités, TP/SL, résultats nets et fréquences.

## Contexte historique fourni

- ETH flow continuation : 6 TP / 0 SL, 3,136107 R ;
- SOL P01 early resumption : 5 TP / 0 SL, 2,692308 R ;
- ETH range fade LONG : 4 TP / 1 SL, 1,688312 R.

Ces valeurs sont uniquement un contexte de recherche. Elles ne constituent ni une garantie, ni une validation hors échantillon, ni une promesse de rentabilité. De nouvelles sessions Samsung et de nouveaux exports diagnostiques restent nécessaires avant toute décision d’activation publique.
