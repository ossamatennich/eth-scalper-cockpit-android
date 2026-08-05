# NMC 2.34.4.8 — Scalp Action V1

## Portée

Cette candidate active `NMC_SCALP_ACTION_V1` comme unique source des nouveaux plans finaux manuels. Elle ne passe aucun ordre et conserve `realTradingAllowed=false`. Les résultats historiques utilisés pour sélectionner les règles sont rétrospectifs et ne garantissent aucun résultat futur.

Les anciens moteurs ETH/SOL restent exécutés pour les diagnostics. Leurs nouvelles confirmations sont supprimées du chemin public et suivies dans `LegacyPublicComparator`. Un plan public persistant créé avant la mise à jour est restauré sans déplacer son entrée, son TP ou son SL et continue jusqu’à son terminal.

## Cinq voies figées et priorité

1. `ETH_SHORT_RANGE_EXTREME_V1` (RAW) : SHORT, `1-rangePosition <= 0.0473856`, TP `2.5A`, SL `1.25A`, score 96.
2. `ETH_CONFIRM_MOVE3_V1` (confirmation legacy) : `sg_move3Norm >= -0.381534`, TP `1.5A`, SL `1.25A`, score 92.
3. `ETH_P01_SHORT_LOW_SOL_MICROVOL_V1` (confirmation legacy) : P01 SHORT, `sol_rv_30 <= 0.0000689415`, TP `2.5A`, SL `1.25A`, score 90.
4. `ETH_CONT_SOL_COVERAGE_V1` (RAW) : continuation, `sol_cov_180 >= 0.982383`, TP `1.5A`, SL `1.0A`, score 88.
5. `ETH_REVERSAL_8M_V1` (RAW) : `eth_dret_480 <= -0.00411754`, TP `2.0A`, SL `1.0A`, score 86.

`A=max(0.35, avgRange20)`. `eth_dret_480` utilise un ancrage causal à 480 secondes. `sol_rv_30` est le RMS des log-returns une seconde sans franchir un trou. `sol_cov_180` est la couverture exacte des 181 slots. Le stockage conserve le dernier quote de chaque seconde, 1 200 points maximum par symbole.

## Entrée, sizing et lifecycle

LONG entre sur l’ask arrondi vers le haut ; SHORT sur le bid arrondi vers le bas. TP et SL sont arrondis de façon conservatrice. Le sizing utilise exclusivement le coût aller-retour ETH de 1,43 USDT :

`quantity=floorToStep(14.55 / (stopDistance + 1.43))`, bornée entre 1 et 7.

Le reward/risk net doit être au moins 0,40 et la perte maximale théorique frais inclus reste sous 14,55 USDT. Le terminal utilise le bid pour LONG, l’ask pour SHORT, le SL en priorité, puis remplit au niveau TP/SL planifié sans bénéficier d’un dépassement.

L’entrée utilisateur est indiquée valable cinq secondes. Son expiration met à jour silencieusement le plan mais n’annule pas la mesure TP/SL et ne déplace jamais les niveaux.

## Rollback local et migration

Le mode persistant est `ACTION_ON` par défaut. `DIAGNOSTICS_ONLY` bloque les nouvelles alertes tout en suivant virtuellement les opportunités. Un changement de mode ne touche jamais au plan actif et une réactivation ne rejoue pas le passé.

`ActivePlanState` passe au format 3 avec lecture rétrocompatible des formats précédents. Le registre d’épisodes est FIFO, borné à 160 et n’ajoute aucun cooldown terminal ; seule une continuité de même côté observée à moins de 180 secondes empêche un doublon.

## Validation et limites

Les tests couvrent les seuils inclusifs, la causalité, les trous de données, la priorité, les arrondis, l’économie frais inclus, les terminaux, la migration, le mode et la suppression legacy. Les protocoles frozen/shadow, l’export complet, la connexion Binance Futures et les alertes existantes sont conservés.

### Correctif après audit

L’arbitrage ne publie plus la première confirmation legacy rencontrée. Pendant chaque cycle, toutes les qualifications legacy sont collectées, puis la qualification RAW est ajoutée ; un arbitre pur choisit ensuite un seul gagnant par priorité, timestamp et identifiant lexical. Les autres qualifications restent diagnostiques, sans persistance, alerte ni épisode marqué comme ouvert.

Juste avant la persistance, une garde pure recontrôle le mode, les trois flux, l’absence de plan actif, la validité de cinq secondes, les quotes et l’économie frais inclus. Les refus tardifs conservent leur cause exacte. Le statut Scalp Action expose désormais `grossLossAtSl`, les frais, la perte totale, `theoreticalMaximumLoss` et `modeledRiskUsdt` de manière cohérente avec le budget frais inclus de 14,55 USDT. Les plans legacy format 2 gardent leur sémantique historique.

Le résumé ajoute le temps réellement frais plafonné à cinq secondes par intervalle, les R positifs/négatifs, les frais, le profit factor, l’expectancy, le drawdown maximal et la fréquence par heure fraîche. Ces mesures restent descriptives et ne garantissent aucun bénéfice.

Les anciens résultats « 16/16 » sont explicitement retirés comme base de décision. Les chiffres de recherche rétrospective (50 ouvertures, 38 TP, +18.003 R, +241.44 USDT) ne sont pas injectés dans les compteurs live et ne sont pas une promesse de rentabilité. Une validation prospective sur appareil et sur de nouvelles données reste nécessaire.
