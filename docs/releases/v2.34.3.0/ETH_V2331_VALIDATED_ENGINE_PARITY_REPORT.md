# Parité du moteur ETH validé v2.33.1

## Référence canonique

La fixture compacte `tools/fixtures/eth_v2331_validated_plans.csv` contient 16 plans validés et son SHA-256 est `4b49d0df47f17783a62c9ef1e7eeedd8f40e61438832f2d86e85a498d80fe7bd`.

- 16 plans ; 16 TP ; 0 SL dans ce corpus historique de recherche ;
- P01 : 7 ; P02 : 9 (TREND 6, REVERSAL 3) ;
- LONG : 6 ; SHORT : 10 ;
- toutes les confirmations publiques ont un âge supérieur ou égal à 15 000 ms ;
- chaque entrée, stop, cible, quantité, timestamp et terminal est contrôlé par `tools/validate_eth_v2331_replay.py`.

Le golden master ETH existant reste inchangé : manifest SHA-256 `cc443c78d8e1b6ff71920b57edb0cdddf329a83919a77957aca7adbbaee503bb`, digest global `dd17b73ee7748179cac67f3b05592b4d53ce96e24f3766763054179c9a56b8d3`. Il couvre 20 000 snapshots déterministes et les frontières P01/P02/OLS60, sizing, signatures, terminaux et réarmement.

## Résultat

Parité : **PASS**. La récupération v2.34.2 ne modifie ni les décisions publiques v2.33.1, ni les seuils, ni les niveaux ou quantités. Le calcul anticipé reste observable, mais son effet public est neutralisé avant 15 secondes.

Ces résultats décrivent un corpus de diagnostic historique. Ils ne prédisent ni ne garantissent un résultat financier futur.
