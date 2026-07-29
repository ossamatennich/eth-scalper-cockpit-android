# Sizing adaptatif v2.34.3

## Politique déterministe

Le stop ne dépend jamais d’une quantité désirée. Après arrondi conservateur :

`riskPerUnit = roundedStopDistance + riskAllowancePerUnit`

`riskQuantity = floor(selectedRiskBudget / riskPerUnit)`

`finalQuantity = min(riskQuantity, qualityCap, profile.maximumQuantity)`, puis application du pas du profil.

Pour SOL, le niveau qualitatif sélectionne les preuves de budget mais ne limite pas le nombre de SOL à 3–7 ; le plafond physique reste 120 SOL. Pour ETH, le plafond qualitatif reste 3–7, mais 1 ou 2 ETH sont autorisés lorsque le risque l’exige.

## Budgets

- 10,00 USDT : défaut, donnée incomplète, contexte non propre ou veto replay historique ;
- 12,00 USDT : sleeve et qualité confirmées, feed frais, données complètes, contexte propre et confluence, sans veto ;
- 14,55 USDT : preuves précédentes + premium 15 minutes + contexte exceptionnel + qualité ≥ 6.

Le score seul produit toujours 10,00 USDT. Aucun uplift, aucun minimum forcé à 3, aucun dépassement silencieux.

## Contrôles

- Stop canonique ETH élargi : 1,28 → 1,70 ; quantité standard 2 → 2 ; risque modélisé 7,26 → 8,10 USDT.
- Distribution canonique au budget standard : 12 plans à 2 ETH et 4 plans à 3 ETH, sans dépassement de 10 USDT.
- Cas naturel SOL P02 : stop 0,07, allowance 0,10, budget standard 10,00, quantité 58 SOL, risque 9,86 USDT (ancien 65 SOL / 11,05 USDT).
- Cas naturel ETH P02 : stop 1,22, allowance 2,35, budget renforcé 12,00, quantité 3 ETH, risque 10,71 USDT.

Le levier affiché (ETH x5, SOL x2) est informatif et n’entre pas dans ces calculs.
