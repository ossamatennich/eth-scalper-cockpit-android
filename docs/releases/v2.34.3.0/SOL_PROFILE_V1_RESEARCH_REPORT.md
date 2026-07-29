# SOL_PROFILE_V1_RESEARCH_REPORT

## Portée

Validation reproductible du profil `SOL_V1_20260727` sur les archives publiques officielles Binance Futures USD-M, du 2024-07-01 au 2026-07-26. Elle valide les distances, la volatilité, le prix et le risque. La validation exacte du flow SOL 15/30/60 secondes reste à obtenir par les diagnostics naturels de la candidate. Les bougies 1m ne sont pas présentées comme un replay exact des flows sous-minute. Aucune garantie de performance.

## Corpus vérifié

- ETHUSDT: 1,088,640 bougies
- SOLUSDT: 1,088,640 bougies
- BTCUSDT: 1,088,640 bougies
- Trous: {'ETHUSDT': 0, 'SOLUSDT': 0, 'BTCUSDT': 0}
- Doublons identiques: {'ETHUSDT': 0, 'SOLUSDT': 0, 'BTCUSDT': 0}
- Doublons conflictuels: 0
- Archives: 150
- Manifest SHA-256: `7f08e6a4f65a3aba56223e517d4166c35e03f472e683a1d7a760be9daf57a5fc`

## Ratio de volatilité relative SOL/ETH

- observations: 1,087,585
- p10: 0.957939
- p25: 1.098397
- médiane: 1.283498
- p75: 1.528929
- p90: 1.835800
- corrélation des rendements 1m ETH/SOL: 0.768061
- contrôle médiane [0,85 ; 1,35]: PASS

## Stabilité mensuelle

- 2024-07: médiane 1.655763 (44,620 observations)
- 2024-08: médiane 1.404439 (44,620 observations)
- 2024-09: médiane 1.366340 (43,180 observations)
- 2024-10: médiane 1.475710 (44,565 observations)
- 2024-11: médiane 1.279105 (43,180 observations)
- 2024-12: médiane 1.325193 (44,620 observations)
- 2025-01: médiane 1.472911 (44,620 observations)
- 2025-02: médiane 1.270284 (40,300 observations)
- 2025-03: médiane 1.349914 (44,620 observations)
- 2025-04: médiane 1.272289 (43,180 observations)
- 2025-05: médiane 1.064152 (44,620 observations)
- 2025-06: médiane 1.208150 (43,180 observations)
- 2025-07: médiane 1.188457 (44,620 observations)
- 2025-08: médiane 1.157804 (44,620 observations)
- 2025-09: médiane 1.456948 (43,180 observations)
- 2025-10: médiane 1.342410 (44,620 observations)
- 2025-11: médiane 1.206678 (43,180 observations)
- 2025-12: médiane 1.228833 (44,620 observations)
- 2026-01: médiane 1.242273 (44,620 observations)
- 2026-02: médiane 1.158423 (40,300 observations)
- 2026-03: médiane 1.136790 (44,620 observations)
- 2026-04: médiane 1.138922 (43,180 observations)
- 2026-05: médiane 1.378270 (44,620 observations)
- 2026-06: médiane 1.348835 (43,180 observations)
- 2026-07: médiane 1.185830 (36,920 observations)

## Contrôles du profil

- 1,088,640 prix SOL contrôlés causalement
- 15,496 calculs de quantité rejetés explicitement hors de la plage 1..120 (aucun clamp silencieux)
- aucune formule `SL_min > SL_max`
- aucune formule `TP_floor > TP_cap`
- distances au tick 0,01
- quantités positives pour les plans autrement valides
- perte modélisée inférieure ou égale au budget de qualité
- seuil de référence `A_min=0.015` confirmé (aucun retour à 0.0147)

Les résultats sont des contrôles de cohérence de recherche, pas une promesse de rentabilité future.
