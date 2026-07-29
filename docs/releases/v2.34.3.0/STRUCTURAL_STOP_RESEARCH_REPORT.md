# Recherche de stop structurel v2.34.3

## Corpus et méthode

Le paquet `NMC_CODEX_SINGLE_PACKAGE_v2.34.2.0_20260728.zip` a été vérifié au SHA-256 `a355f99251795256014de9f2046853169603fbe22caff79595098e84fdffac30`. Il contient 14 sessions brutes ; les 16 plans canoniques proviennent de 9 sessions tradées. Les frames postérieures à `confirmationAt` sont exclues. Les prix 5 secondes sont agrégés en OHLC minute et seule une minute terminée est admissible.

Le validateur reproductible est `tools/validate_structural_stop_research.py`.

## Grille comparée après validation de cohérence de l’ancre

| Fenêtre / buffer | Plans valides | Ancres | Structure dominante | Hausse moyenne | Hausse max |
|---|---:|---:|---:|---:|---:|
| 5m / 0,15A | 16 | 2 | 1 | 0,02625 | 0,42 |
| 5m / 0,20A | 16 | 2 | 2 | 0,030625 | 0,48 |
| 5m / 0,25A | 16 | 2 | 2 | 0,03625 | 0,55 |
| 5m / 0,35A | 16 | 2 | 2 | 0,04750 | 0,68 |
| 8m / 0,15A | 16 | 3 | 1 | 0,02625 | 0,42 |
| 8m / 0,20A | 16 | 3 | 2 | 0,030625 | 0,48 |
| 8m / 0,25A | 16 | 3 | 2 | 0,03625 | 0,55 |
| 8m / 0,35A | 16 | 3 | 2 | 0,04750 | 0,68 |
| 15m / 0,15A | 16 | 4 | 2 | 0,100625 | 1,19 |
| 15m / 0,20A | 16 | 4 | 3 | 0,110625 | 1,28 |
| 15m / 0,25A | 16 | 4 | 3 | 0,121875 | 1,37 |
| 15m / 0,35A | 16 | 4 | 3 | 0,14500 | 1,56 |

La configuration `5m / 0,15A` est retenue : elle est la plus parcimonieuse, protège une structure locale confirmée et minimise l’élargissement. Les ancres dont la distance excède `1,50A` sont classées incohérentes, pas transformées en stop. L’enveloppe d’intégrité a été atteinte 0 fois.

## Distribution des stops ETH canoniques

| Distribution | Minimum | Médiane | Moyenne | Maximum |
|---|---:|---:|---:|---:|
| Ancien | 0,55 | 1,44 | 1,42375 | 2,34 |
| Nouveau | 0,55 | 1,51 | 1,45000 | 2,34 |

Le `baseStop` domine 15 fois ; la structure domine 1 fois. Le maximum n’est donc ni systématique ni utilisé comme valeur de stop.

## Tableau des 16 plans

| Session | Sleeve | Side | Confirmation | Stop ancien | Stop nouveau | Qté ancienne | Qté standard nouvelle | Résultat |
|---|---|---|---:|---:|---:|---:|---:|---|
| 20260722_120527 | P01 | LONG | 1784683583449 | 1,37 | 1,37 | 2 | 2 | TP |
| 20260722_120527 | P01 | LONG | 1784688026315 | 1,28 | 1,70 | 2 | 2 | TP |
| 20260722_120527 | P02 REVERSAL | SHORT | 1784712815163 | 0,75 | 0,75 | 3 | 3 | TP |
| 20260722_193416 | P02 TREND | SHORT | 1784721799190 | 0,91 | 0,91 | 3 | 3 | TP |
| 20260722_193416 | P01 | LONG | 1784725173771 | 1,66 | 1,66 | 2 | 2 | TP |
| 20260722_231944 | P02 TREND | SHORT | 1784744576487 | 1,86 | 1,86 | 2 | 2 | TP |
| 20260722_231944 | P02 TREND | SHORT | 1784745403074 | 2,10 | 2,10 | 2 | 2 | TP |
| 20260723_200606 | P01 | SHORT | 1784818316391 | 2,10 | 2,10 | 2 | 2 | TP |
| 20260723_200606 | P01 | LONG | 1784819855421 | 2,34 | 2,34 | 2 | 2 | TP |
| 20260724_101017 | P02 REVERSAL | LONG | 1784845301313 | 1,51 | 1,51 | 2 | 2 | TP |
| 20260724_101017 | P02 TREND | SHORT | 1784853180434 | 1,51 | 1,51 | 2 | 2 | TP |
| 20260724_135021 | P01 | SHORT | 1784880701027 | 1,01 | 1,01 | 2 | 2 | TP |
| 20260724_172126 | P02 TREND | SHORT | 1784898411469 | 2,01 | 2,01 | 2 | 2 | TP |
| 20260724_211400 | P02 TREND | SHORT | 1784910770209 | 1,17 | 1,17 | 2 | 2 | TP |
| 20260726_140817 | P01 | SHORT | 1785045973950 | 0,55 | 0,55 | 3 | 3 | TP |
| 20260726_140817 | P02 REVERSAL | LONG | 1785047742178 | 0,65 | 0,65 | 3 | 3 | TP |

Parité : 16 décisions, 7 P01, 9 P02, mêmes timestamps, 16 TP, 0 SL, aucun plan public avant 15 secondes. Le planner est exécuté après la sélection ; il n’utilise aucune frame future. Les stress historiques restent ceux du TP et des terminaux validés ; ce rapport ne revendique aucune garantie financière.
