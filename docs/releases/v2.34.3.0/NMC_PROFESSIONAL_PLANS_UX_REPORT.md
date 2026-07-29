# NMC — Professional Plans UX

## Architecture

- `PlanUiModel` : contrat immuable d’un plan affiché ;
- `PlanUiMapper` : lecture du JSON canonique `ActivePlanState/status` ;
- `PlanMetricsCalculator` : calcul pur des métriques de présentation ;
- `ActivePlanCardView` : composant Android réutilisable pour tout symbole.

Les cartes ETH/SOL sont indépendantes, ordonnées comme les plans actifs et mises à jour en place. Une crypto future utilisant le même contrat produit une carte sans copie de l’écran. Le scroll et l’onglet sélectionné restent gérés par l’activité native existante.

## Données affichées

Chaque carte montre symbole, side, quantité/actif, état immuable TP/SL, feed, LIMIT, TP, SL, cours, bid/ask, score, sleeve, âge, progression, distances, gains/pertes bruts, estimation nette après coût, frais, perte maximale modélisée, budget, R/R, levier visuel, notionnel et marge indicative. Le levier ETH x5 / SOL x2 est strictement une visualisation; il n’est relié à aucune exécution.

Les formules de présentation sont :

- gain brut = `abs(TP-entry) × quantité` ;
- perte brute = `abs(entry-SL) × quantité` ;
- frais estimés = `resultCostPerUnit × quantité` ;
- gain net estimé = gain brut − frais ;
- perte nette estimée = perte brute + frais ;
- perte maximale modélisée = `quantité × (stopDistance + riskAllowancePerUnit)`.

## Sécurité UX

Une valeur obligatoire manquante produit `DONNÉE INDISPONIBLE` et `PLAN_UI_DATA_INCOMPLETE`. Les niveaux publiés ne sont jamais modifiés. Les actions de copie n’envoient aucun ordre. La notification finale inclut score et sleeve et ouvre l’onglet Plans; restauration, TP et SL restent silencieux.
