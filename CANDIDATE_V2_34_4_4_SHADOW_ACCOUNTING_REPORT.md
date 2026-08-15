# NMC v2.34.4.4 — Shadow Accounting Report

## Portée

Cette candidate reste exclusivement **SHADOW A/B OBSERVABILITY ONLY**. Aucun filtre, signal, timing, niveau, sizing, terminal, réarmement, plan persistant ou avertissement public n’est modifié. `realTradingAllowed=false` demeure un invariant.

## Corrections

- Les confirmations publiques, qualifications shadow, occasions exécutables et ouvertures sont désormais conservées dans des ensembles sémantiques séparés et bornés.
- Les totaux combinés sont de vraies unions de `movementKey`, sans soustraction approximative ni comptage d’un simple rejet.
- Un registre FIFO de 256 ouvertures conserve signature, mouvement et terminal. Un overlap public est donc retrouvé après un TP ou un SL shadow, une seule fois.
- Les terminaux incluent `movementKey`, `publicOverlap` et `higherPriorityOverlap`; les index sont nettoyés ensemble lors d’une éviction.
- Les plans shadow ajoutés utilisent réellement la quantité frais inclus. Une quantité sous le minimum produit `SHADOW_FEE_AWARE_QUANTITY_UNAVAILABLE`; la quantité publique observée n’est jamais changée.
- Les sondes publiques exposent `activeQuantity`; les lanes ajoutées exposent `baselineGrossQuantity` et `shadowQuantity` sans prétendre posséder une quantité publique.
- Les doublons supprimés sont additionnés par candidat et par composant. La médiane qualification–ouverture est calculée sur au plus 256 mesures.
- Le ZIP expose les résumés `ALL`, `ETHUSDT` et `SOLUSDT`.
- Toutes les opérations de résumé, overlap, sizing, terminal et reset shadow sont fail-open. Le terminal public est entièrement achevé avant la comptabilité shadow.

## Validation

Les tests fonctionnels couvrent les unions exactes, les rejets non comptés comme occasions, les overlaps actifs et post-terminaux, l’éviction 257→256, la quantité fee-aware réellement utilisée, les agrégats, les médianes, les doublons cumulés et les défaillances volontaires du résumé.

La validation réelle sur appareil Samsung et sur de nouvelles sessions hors échantillon reste nécessaire. Ces statistiques de recherche ne constituent ni une promesse de rentabilité ni une autorisation de trading réel.
