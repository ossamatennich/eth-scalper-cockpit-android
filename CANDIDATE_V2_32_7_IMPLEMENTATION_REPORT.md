# Candidate v2.33.2 — rapport d’implémentation

> Le nom historique du fichier est conservé. L’application produite est **ETH Scalper Cockpit v2.33.2 — Timely P01 + Quantity Uplift** (`versionCode 23320`, `versionName 2.33.2`).

## Référence et sécurité

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`.
- Branche unique : `agent/v2.32.7-scalp-p01-candidate`.
- HEAD de départ vérifié : `a0d520deca88de9f7f875da20d24c3fe41aaa7f1`.
- PR nº 2 : base `agent/v2.32.6-candidate`, conservée ouverte, brouillon et non fusionnée.
- `main` inchangée ; aucune release créée.
- Profil `RESEARCH_ONLY`, `realTradingAllowed=false`, aucun ordre automatique, aucune clé exchange et IA non décisionnelle.

## TIMELY P01 CONFIRMATION

`P01EarlyConfirmation.java` est une classe pure, déterministe et symétrique LONG/SHORT. Elle ajoute deux voies strictement limitées aux CONTINUATION P01 âgées de moins de 15 secondes :

- `GUARDED_CURRENT_P01` : filtre P01 courant accepté, puis gardes `m8 <= 4.00`, `m8 >= -1.80 OR m1 >= 1.50`, et `m8 <= 3.00 OR room >= 2.40` ;
- `STRUCTURE_LED` : bornes exactes demandées sur `m1`, `m3`, `m8`, `room`, `f30`, `f60` et `volumeRatio`.

La tolérance de frontière est `EPS=1e-12`. Toutes les préconditions restent obligatoires : feed et snapshot frais/causaux, LIMIT courante exécutable, entrée originale inchangée, C04/C07/C08/P01 acceptés, métriques normalisées valides, plan dynamique valide, aucun plan actif et réarmement terminé.

La même voie doit rester vraie sans interruption pendant 1 000 ms. La première observation ne publie rien. Une rupture, un changement de voie, un feed stale, une LIMIT non exécutable, un snapshot non frais, un plan actif ou un réarmement réinitialise immédiatement la stabilité. À `ageMs >= 15 000`, la branche anticipée est remise à zéro et le lifecycle normal v2.33.1 reprend sans changement de `CandidateLifecycle.MIN_CONFIRMATION_AGE_MS`.

`MarketWatchService` transforme le même `ObservedSignal` en `ACTIVE` et réutilise le chemin commun : verrou mono-plan, sizing qualitatif, plan dynamique, persistance atomique préalable, signature déterministe, notification sonore unique et avis IA asynchrone après publication. Un échec de persistance annule la publication.

Les diagnostics ajoutent l’éligibilité, la voie, le début et la durée de stabilité, le reason code, l’âge de confirmation, les métriques normalisées, bid/ask, entrée, distance quote/LIMIT et secondes économisées par rapport à la première échéance normale disponible.

## QUANTITY UPLIFT

Les niveaux restent calculés avec les formules v2.33.1 inchangées. Deux budgets sont désormais explicitement séparés :

- `LEGACY_RISK_BUDGET_USDT = 10.00`, utilisé uniquement pour retrouver la quantité de référence v2.33.1 ;
- `DEFAULT_RISK_BUDGET_USDT = 14.55`, utilisé uniquement pour autoriser l’uplift.

Le calcul exact est :

```text
legacyRiskQuantity = floor(10.00 / riskPerEth)
baselineFinalQuantity = min(legacyRiskQuantity, boundedQualityCap, 7)
upliftedQuantity = min(7, max(3, baselineFinalQuantity + 1))
```

Le résultat est donc exactement `1→3`, `2→3`, `3→4`, `4→5`, `5→6`, `6→7`, `7→7`. Un ancien 2 ETH ne devient jamais 4 ETH. Le plan est refusé avec `V2332_QUANTITY_UPLIFT_RISK_REJECTED` si la quantité augmentée dépasse `floor(14.55/riskPerEth)` ou si sa perte maximale modélisée dépasse `14.55 + 1e-9` ; aucune réduction silencieuse n’est appliquée.

Les diagnostics exposent les deux budgets, quantité risque legacy, baseline, uplift appliqué, quantité autorisée par le nouveau budget et pertes théoriques avant/après uplift. La quantité finale est la même dans le plan, l’écran, la notification, la persistance et les diagnostics.

## Éléments inchangés

- P02 et `TrendRegime60` : code source inchangé ; P02 appelle explicitement le calcul legacy pour conserver sa quantité v2.33.1.
- Entrée candidate, formules et arrondis TP/SL inchangés.
- `RESULT_ROUND_TRIP_COST_PER_ETH=1.43` et `RISK_EXECUTION_ALLOWANCE_PER_ETH=2.35` inchangés.
- Création des candidats, OLS60, réarmement terminal 180 secondes, RANGE_FADE diagnostic-only, persistance/restauration, notification unique et verrou mono-plan inchangés.
- Après publication : aucune sortie anticipée, aucun break-even, trailing, timeout ou invalidation analytique ; fin uniquement par `TP_TOUCHED` ou `SL_TOUCHED`.

## Corpus et limites

Les valeurs de contrôle fournies couvrent 14 sessions, 39 695 frames uniques et environ 77,14 heures. Les fichiers de session exacts n’étaient pas présents localement : les tests emploient donc des fixtures déterministes construites à partir des valeurs fournies, sans fabriquer un replay historique. Les contrôles annoncés (7 cas positifs de branche, cas négatifs connus, audit 15 plans `2→3` et 4 plans `3→4`) sont des références de recherche, pas une garantie de performance future.
