# NMC v2.34.2.0 — rapport d’implémentation

## Références vérifiées

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche : `agent/v2.32.7-scalp-p01-candidate`
- HEAD de départ : `305d054be7ec42d02a8f130f9a682dab3350cb0c`
- PR : nº 2, ouverte, brouillon, non fusionnée, base `agent/v2.32.6-candidate`
- `main` vérifiée inchangée à `7edd5719c3f767f86726ef58c405974515763f4e`
- Paquet d’instructions : SHA-256 `a355f99251795256014de9f2046853169603fbe22caff79595098e84fdffac30`
- Manifeste du paquet : 30 fichiers vérifiés, zéro écart.

## Version produite

- `versionCode 23420`
- `versionName 2.34.2.0`
- Produit : NMC — Native Market Cockpit
- Mode : `RESEARCH_ONLY`
- `realTradingAllowed=false`

## Validated engine recovery

Le timing public ETH validé en v2.33.1 est restauré. Aucun P01 public ne peut être publié avant 15 000 ms. Le chemin `P01EarlyConfirmation` reste calculé avec ses règles existantes, mais uniquement sous le scope `SHADOW_RESEARCH`. Il produit `EARLY_P01_SHADOW_WOULD_CONFIRM` ou `EARLY_P01_SHADOW_REJECTED` et ne peut ni créer/persister un plan, ni consommer la déduplication métier, ni produire une alerte.

À partir de 15 000 ms, le lifecycle public existant reprend sans modification : quote courante exécutable, feed frais, revalidation, C04/C07/C08, filtre P01 v2.33.1, plan dynamique, persistance atomique puis notification finale unique. Les fenêtres P02 20 000/45 000 ms et OLS60 ne sont pas modifiées.

## Sizing

Les deux politiques pures existantes restent disponibles :

- `DynamicTradePlan.calculateLegacy(...)` : référence v2.33.1, budget 10,00 USDT, quantités 1–7 ;
- `DynamicTradePlan.calculate(...)` : uplift v2.33.2, budget final 14,55 USDT.

Aucune troisième formule n’a été ajoutée. Les formules d’entrée, TP, SL, coût 1,43, allowance 2,35, reward/risk et arrondis sont inchangées.

## Professional Plans UX

L’écran Plans utilise désormais un modèle immuable et des cartes indépendantes par symbole : `PlanUiModel`, `PlanUiMapper`, `PlanMetricsCalculator` et `ActivePlanCardView`. Il affiche les niveaux publiés, cours/bid/ask, âge, feed, score, sleeve, progression, distances, résultats bruts/nets, frais estimés, risque, budget, R/R, levier visuel, notionnel et marge indicative. Les boutons copient LIMIT, TP, SL ou le plan complet.

Les données obligatoires absentes sont affichées `DONNÉE INDISPONIBLE` avec `PLAN_UI_DATA_INCOMPLETE`; elles ne sont jamais remplacées par zéro. Les cartes sont créées dynamiquement depuis la collection des plans et réutilisées sans reconstruction à chaque status. Le clic sur une notification finale ouvre directement l’onglet Plans. L’historique récent est borné à 20 événements de plan; `PLAN_RESTORED` n’est pas un nouveau trade.

## Invariants préservés

- un plan actif maximum par symbole ; ETH et SOL peuvent rester actifs simultanément ;
- BTC contexte uniquement ; RANGE_FADE diagnostic uniquement ;
- aucun ordre automatique, aucune API privée d’exchange ;
- IA informative après publication seulement ;
- plan publié immuable ; fin exclusivement par `TP_TOUCHED` ou `SL_TOUCHED` ;
- persistance/restauration, réarmement 180 s et notification forte v2.34.1.1 conservés ;
- aucune seconde alerte pour une restauration ou un terminal.

## Limites honnêtes

Les résultats historiques sont des contrôles de recherche, pas une garantie de performance future. Les deux diagnostics naturels récents se terminent avant de fournir, pour chaque candidat anticipé supprimé, une fenêtre complète de 90 secondes sans le verrou du plan anciennement publié. Ces cas sont donc documentés sans inventer un résultat contrefactuel.
