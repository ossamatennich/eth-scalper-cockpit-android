# Validation du moteur courant

## Stop causal multi-marchés

Pour chaque marché, avec ses propres prix, bougies, spread, tick et profil :

```text
A = avgRange20 courant valide
technicalBuffer = max(0,15 × A, spread + tick)
structuralProtection = distance(entry, dernier pivot causal) + technicalBuffer
volatilityProtection = A
adverseProtection = E + max(0,20 × A, spread + tick)
finalStopDistance = max(structuralProtection, volatilityProtection, adverseProtection)
```

Si aucun pivot local valide n’est disponible, le dernier `recentLow` sous l’entrée (LONG) ou `recentHigh` au-dessus de l’entrée (SHORT) peut servir de niveau causal. À défaut, la volatilité et l’excursion adverse protègent le plan. Les bougies futures et les valeurs invalides sont ignorées.

Une enveloppe d’intégrité volontairement large rejette une donnée techniquement aberrante mais ne réduit jamais le stop. Le TP dynamique existant et le seuil brut de rendement/risque `1,40` sont inchangés ; un ratio insuffisant refuse le plan sans rapprocher le SL.

## Sizing hors frais

```text
grossRiskPerUnit = abs(entry - stopLoss)
riskQuantity = floorToQuantityStep(14,55 / grossRiskPerUnit)
finalQuantity = min(riskQuantity, qualityQuantityCap, marketMaximumQuantity)
grossLossAtSl = grossRiskPerUnit × finalQuantity
estimatedRoundTripFees = resultCostPerUnit × finalQuantity
estimatedTotalLossAtSl = grossLossAtSl + estimatedRoundTripFees
```

La perte brute doit rester inférieure ou égale à 14,55 USDT. Les frais ne sont jamais mélangés à cette limite. Si la quantité minimale du profil dépasse le budget brut, le plan est refusé ; le stop n’est jamais resserré.

ETH utilise son plafond qualitatif en unités. SOL traduit son niveau qualitatif avec ses budgets et son pas de quantité propres. Aucun nombre d’unités ETH n’est copié vers SOL.

## Limites

Cette validation porte sur la causalité, les calculs de risque, l’isolation des marchés et les invariants de lifecycle. Le replay historique retiré n’est plus une condition de publication. Aucune performance future n’est garantie.
