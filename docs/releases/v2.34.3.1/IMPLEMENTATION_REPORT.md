# NMC v2.34.3.1 — rapport d’implémentation

La candidate remplace dans le chemin public les anciennes bornes absolues de stop par une invalidation causale. Le dernier pivot local terminé est recherché dans la fenêtre de cinq minutes ; `recentLow` ou `recentHigh` peut servir de repli causal. Le buffer combine volatilité, spread et tick. La distance finale couvre la structure, `A` et l’excursion adverse observée.

Le dimensionnement intervient ensuite. La perte brute entrée–SL est plafonnée à 14,55 USDT. Les frais aller-retour sont calculés et affichés séparément, puis ajoutés uniquement à la perte totale estimée. Un stop trop large entraîne un refus si la quantité minimale n’est pas compatible ; il n’est jamais rapproché.

ETH et SOL restent isolés dans leurs runtimes et utilisent leurs propres profils, données, ticks, coûts et pas de quantité. BTC reste un contexte partagé. Les filtres d’entrée, les timings P01/P02, le TP, les alertes, la persistance et le lifecycle TP/SL n’ont pas été modifiés.

Les anciens replays historiques, fixtures et golden manifests utilisés uniquement pour comparer des versions passées ont été retirés du produit actif et de la CI. Les diagnostics naturels et le validateur SOL sont conservés.

Cette candidate est `RESEARCH_ONLY`, avec `realTradingAllowed=false`. Elle n’offre aucune garantie financière.
