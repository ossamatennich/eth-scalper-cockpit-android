# Validation du moteur

## Référence ETH

Le replay canonique couvre 16 plans : 7 P01, 9 P02, 16 TP et 0 SL. Aucun plan public n’est publié avant 15 secondes.

## Stop structurel v2.34.3.0

```text
A = max(profile.aMinimumScaled(entry), avgRange20)
baseStop = max(profile.stopMinimumScaled(entry), A, E + 0.20 × A)
structuralStop = structureDistance + 0.15 × A
requiredStop = max(baseStop, structuralStop)
```

La structure utilise uniquement les bougies terminées disponibles à la confirmation, sur une fenêtre de 5 minutes. L’enveloppe d’intégrité rejette les données aberrantes et ne rapproche jamais le stop.

Le TP validé reste `clamp(2.70 × A + 0.20 × R, TP_floor, TP_cap)` et le rendement/risque brut doit être au moins `1,40`.

## Sizing

Le sizing est calculé après le stop avec des budgets déterministes de 10,00, 12,00 ou 14,55 USDT selon des preuves cumulatives. Le score seul ne relève jamais le budget. Aucun minimum artificiel à 3 et aucun uplift ne sont appliqués.

Les rapports détaillés et les limites de la validation sont conservés dans `docs/releases/v2.34.3.0/`. Aucun résultat historique ne constitue une garantie financière.
