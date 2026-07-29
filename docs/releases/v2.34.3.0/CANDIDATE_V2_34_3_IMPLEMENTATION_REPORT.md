# NMC v2.34.3.0 — rapport d’implémentation

## Référence

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche : `agent/v2.32.7-scalp-p01-candidate`
- HEAD parent : `9024ffcbaddf341ddc7e10664bf549b1b2e6d73a`
- Version : `versionCode 23430`, `versionName 2.34.3.0`
- Périmètre : stop structurel final et sizing de risque après stop. La sélection P01/P02, les timings, OLS60, les entrées et le TP ne sont pas recalibrés.

## Stop structurel final

`StructuralStopPlanner` est pur, causal, symétrique et piloté par `MarketProfile`.

1. `A = max(profile.aMinimumScaled(entry), avgRange20)`.
2. `baseStop = max(profile.stopMinimumScaled(entry), A, adverseExcursion + 0,20 × A)`.
3. Recherche du dernier pivot local confirmé dans les bougies 1 minute terminées uniquement.
4. Configuration retenue : fenêtre 5 minutes, buffer `0,15 × A`.
5. Une ancre doit être du bon côté, confirmée par les deux clôtures voisines, consécutive et à une distance cohérente `≤ 1,50 × A`. Sinon : `STRUCTURAL_ANCHOR_UNAVAILABLE` et repli sur `baseStop`.
6. `requiredStop = max(baseStop, structureDistance + buffer)`.

L’ancien plafond métier `min(2,50, 2A)` n’est pas utilisé dans ce parcours. L’enveloppe d’intégrité `max(12A, 12×stopMinimum, 3 % de l’entrée)` rejette seulement une donnée aberrante ; elle ne clampe jamais le SL.

Le TP reste strictement : `clamp(2,70A + 0,20R, max(targetFloor, 1,95×cost), targetCap)`. Le plan est refusé si le R/R arrondi est inférieur à 1,40, sans resserrer le stop et sans éloigner le TP.

## Sizing adaptatif après le stop

Ordre effectif : confirmation → stop → TP → R/R → budget → quantité.

- standard : 10,00 USDT ;
- renforcé : 12,00 USDT, seulement avec sleeve acceptée, qualité confirmée, contexte propre, confluence, feed parfaitement frais, données complètes et aucun veto replay ;
- premium : 14,55 USDT, avec toutes les preuves renforcées, premium 15 minutes, contexte exceptionnel et plafond qualité ≥ 6.

Le score seul ne peut pas relever le budget. `riskPerUnit = roundedStopDistance + riskAllowancePerUnit`, puis `floor(budget/riskPerUnit)`, pas de quantité du profil, plafond qualité ETH ou maximum SOL. Aucun minimum artificiel de 3 et aucun quantity uplift.

## Runtime, persistance et interface

- Les pipelines de sélection ETH et SOL restent indépendants ; tous deux transmettent leurs bougies terminées au même planner.
- Le plan actif persiste A, E, base, ancre, fenêtre, buffer, type de stop, raison du budget, risque/unité, quantité de risque et plafond qualité.
- La carte Plans ajoute « POURQUOI CE STOP ? » et le détail du sizing. Une donnée obligatoire absente affiche `DONNÉE INDISPONIBLE`, jamais zéro.
- Entrée, TP, SL et quantité sont figés après publication. Seuls `TP_TOUCHED` et `SL_TOUCHED` terminent le plan.
- L’alerte sonore centrale `nmc_final_signal_loud_v1`, l’export streaming, la persistance multi-marchés et `realTradingAllowed=false` restent inchangés.

## Limites honnêtes

Les frames historiques à 5 secondes permettent une reconstruction causale des bougies minute, mais ne constituent ni des quotes exchange exhaustives ni une garantie d’exécution manuelle. Le profil SOL reste `RESEARCH_ONLY`. Aucun résultat passé ne garantit un résultat futur.
