# NMC 2.34.4.9 — CV Core V1

## Portée

`NMC_SCALP_CV_CORE_V1` remplace entièrement le moteur public 4.8 pour les nouvelles décisions. Il émet uniquement des plans manuels ETH ; aucun signal public SOL, ordre Binance, API privée ou exécution automatique n’est ajouté. `realTradingAllowed=false` reste un invariant.

Le corpus de recherche porte l’empreinte `bc7e43bb650518d96dbd0bb1034d43bf792d36a215a315b7100c997bdc35ed93` : 4 914 observations exécutables, 840 épisodes structurels et 83,4541667 heures de flux frais, du 30 juillet au 6 août 2026.

## Épisodes et contexte causal

Toute décision RAW ETH signal et toute confirmation ETH P01/P02 crée ou prolonge d’abord un épisode `symbol|side`. Un écart strictement supérieur à 180 secondes crée un nouvel épisode. Une observation non qualifiante prolonge aussi l’épisode ; une route ne peut donc jamais modifier rétrospectivement le regroupement.

Le contexte conserve au plus 1 200 quotes d’une seconde par symbole. Les rendements directionnels utilisent un anchor causal à 60 secondes. Les efficacités divisent le déplacement signé par la distance totale du chemin observé, sans échantillon futur ni interpolation future.

## Trois voies figées

1. `ETH_RANGE_DUAL_EXHAUSTION_SHORT_V1` — RAW SHORT `RANGE_FADE`, rendement SOL 60 s ≤ −0,00030, rendement ETH 60 s ≤ −0,00035, efficacité ETH 60 s > −0,40. TP 4 A, SL 1,75 A, budget 14,55 USDT.
2. `ETH_CAPITULATION_LONG_V1` — RAW LONG `RANGE_FADE`, mouvement BTC 8 directionnel ≤ −0,0016 et rendement ETH 60 s ≤ −0,0010. TP 2,5 A, SL 1,5 A, budget 14,55 USDT.
3. `ETH_P02_CONFIRMED_BALANCED_SHORT_V1` — confirmation P02 SHORT `CONTINUATION`, BTC 3 directionnel ≤ 0,0002, `m3` normalisé directionnel ≤ 2,0 et efficacité SOL 30 s > 0. TP 3 A, SL 1,25 A, budget exact 7,275 USDT.

Les voies A et B ont été figées avant les sessions des 4–6 août. La voie C est post-corpus et volontairement à demi-risque. Une qualification multiple est arbitrée une seule fois par cycle selon priorité, heure puis identifiant lexical.

## Sizing et lifecycle

L’entrée utilise l’ask pour LONG et le bid pour SHORT. TP et SL utilisent les arrondis conservateurs du profil ETH. Le coût aller-retour vaut 1,43 USDT par unité. La quantité est calculée avec le budget de la route, le pas, le minimum et le maximum ETH ; le RR net doit rester au moins égal à 0,40.

L’entrée affichée est valable cinq secondes. Le plan reste ensuite suivi sans déplacer ses niveaux : LONG sur bid, SHORT sur ask, priorité au SL, fill au niveau planifié, aucun timeout, trailing, breakeven ou cooldown terminal.

## Résultats rétrospectifs et limites

Le résultat central rétrospectif est de 44 ouvertures, 43 résolues, 37 TP / 6 SL, environ +278,77 USDT nets estimés et un profit factor net de 4,882, soit environ une occasion toutes les 1 h 54. Ces chiffres ne sont jamais importés dans les compteurs live et ne garantissent aucun résultat futur.

Une validation hors échantillon sur de nouvelles sessions Samsung reste nécessaire. Le moteur ne promet aucune rentabilité.

## Validation logicielle

La suite 4.9 ajoute 92 tests JVM couvrant les seuils, la causalité, les épisodes fixes, l’arbitrage, le sizing, la garde finale, le lifecycle, la migration, la suppression legacy, les diagnostics et le nettoyage du runtime. Après remplacement des 68 tests 4.8 obsolètes, le total passe de 575 à 599 tests par variante.

Les validations locales exécutées sont : 599/599 en Debug, Stable et Release, zéro failure/error/skipped ; 9/9 pour le validateur Python SOL ; construction des trois APK ; `lintRelease` sans erreur. La CI GitHub constitue la validation de signature Stable durable.
