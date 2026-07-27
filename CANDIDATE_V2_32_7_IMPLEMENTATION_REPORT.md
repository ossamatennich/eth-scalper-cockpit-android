# Candidate v2.33.2.1 — rapport d’implémentation

> Le nom historique du fichier est conservé. L’application produite est **ETH Scalper Cockpit v2.33.2.1 — All-Sleeve Quantity Uplift** (`versionCode 23321`, `versionName 2.33.2.1`).

## Référence et périmètre

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`.
- Branche unique : `agent/v2.32.7-scalp-p01-candidate`.
- HEAD de départ vérifié : `d17ada3d25733acaad991614455ae82a085be966`.
- PR nº 2 : base `agent/v2.32.6-candidate`, conservée ouverte, brouillon et non fusionnée.
- `main` inchangée ; aucune release créée.
- Profil `RESEARCH_ONLY`, `realTradingAllowed=false`, aucun ordre automatique, aucune clé exchange et IA non décisionnelle.

## ALL-SLEEVE QUANTITY UPLIFT

Le défaut v2.33.2 était limité au raccordement final P02 : `CandidateLifecycle.confirmAtFill(...)` appelait encore `DynamicTradePlan.calculateLegacy(...)`. Ce branchement est remplacé par l’appel commun `DynamicTradePlan.calculate(...)` déjà utilisé par P01.

Tous les plans finaux publiables P01 et P02 appliquent désormais exactement :

```text
legacyRiskQuantity = floor(10.00 / riskPerEth)
baselineFinalQuantity = min(legacyRiskQuantity, boundedQualityCap, 7)
upliftedQuantity = min(7, max(3, baselineFinalQuantity + 1))
```

Le mapping reste `1→3`, `2→3`, `3→4`, `4→5`, `5→6`, `6→7`, `7→7`. Le budget historique de 10,00 USDT calcule uniquement la baseline. Le budget final de 14,55 USDT autorise l’uplift ; tout dépassement est rejeté explicitement sans réduction silencieuse. `calculateLegacy(...)` reste disponible uniquement pour les tests comparatifs et n’est utilisé par aucun plan final publié.

La quantité P02 finale est copiée sans mutation dans le plan, l’écran, la notification, la persistance et les diagnostics.

## Éléments strictement inchangés

- `P02SleeveFilter.java`, fenêtre 20–45 secondes, régimes TREND/REVERSAL et `TrendRegime60.java`/OLS60 ;
- confirmation anticipée P01, ses deux voies, ses seuils et sa stabilité de 1 000 ms ;
- prix `candidate.entry`, formules et arrondis TP/SL ;
- constantes de coût 1,43 et réserve de risque 2,35 ;
- réarmement terminal 180 secondes, persistance atomique, restauration silencieuse et verrou mono-plan ;
- RANGE_FADE diagnostic-only et `realTradingAllowed=false` ;
- plan final immuable et fin exclusivement par `TP_TOUCHED` ou `SL_TOUCHED`.

Le contrôle naturel fourni pour la session `20260727_105237` est représenté par une fixture déterministe : la baseline 2 ETH devient 3 ETH, avec entrée/TP/SL identiques, et le net contrefactuel passe de `-5,38` à `-8,07 USDT` uniquement par multiplication de quantité. Il s’agit d’un contrôle de recherche, pas d’une garantie financière.
