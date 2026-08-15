# NMC 2.34.5.0 — laboratoire causal et capture prospective

## Décision

Cette version ne remplace pas le moteur public par une règle sélectionnée sur le passé. Aucun
candidat ETH/SOL étudié le 8 août 2026 n'a franchi les critères de robustesse de développement.
Le moteur public reste donc inchangé et `realTradingAllowed=false`.

Cette décision n'est pas un abandon de la recherche : elle retire les deux causes principales du
cycle d'optimisation précédent — les replays incomplets et la réutilisation répétée des mêmes
sessions — en ajoutant un laboratoire reproductible et une capture prospective causale.

## Corpus historiques examinés

### Archives publiques Binance Futures USD-M

- ETHUSDT, SOLUSDT et BTCUSDT en bougies une minute closes ;
- développement limité physiquement à janvier 2025–mars 2026 ;
- SHA-256 du manifeste de développement :
  `870a0a046ba1e1b18b1a5684bef2f05cc4c053e2acb8d416e475e3a2e7ff89b1` ;
- entrée simulée sur la barre suivante, frais de profil inclus et SL prioritaire sur une barre
  ambiguë ;
- zéro configuration acceptée parmi les 128 règles préenregistrées ;
- les archives d'avril–juillet 2026 n'ont pas été ouvertes par le runner corrigé.

Le laboratoire purge désormais l'horizon futur à la fin de chaque split et reconstruit toutes les
fenêtres après une interruption. Un feature multi-minute, une efficacité, une moyenne ou un label
ne peut plus traverser silencieusement un trou du flux.

### Quatorze diagnostics historiques

Le paquet externe observé porte l'empreinte
`a355f99251795256014de9f2046853169603fbe22caff79595098e84fdffac30`. Il contient environ
77,143 heures et 39 696 frames ETH/BTC, mais aucune cotation SOL. Sa résolution médiane est de
5,865 secondes et il contient des colonnes futures explicitement exclues de toute entrée de règle.

Une exploration bornée de 24 configurations causales a trouvé au mieux 51 ouvertures, dont
46 résolues, 28 TP et 18 SL. Malgré 60,9 % de réussite nominale, l'espérance n'était que de
0,038 R/trade, le PF d'environ 1,10 et l'intervalle bootstrap par session traversait largement zéro.
L'avantage devenait négatif avec des coûts ou une latence plus réalistes. Ce motif n'est pas activé.

## Contrôle du surapprentissage

Au moins 296 variantes et analyses ont été consultées au total pendant les cycles de recherche.
Elles sont toutes comptées comme essais, y compris les pertes. Le meilleur ridge ETH observé était
dominé par un seul gain : après retrait de ce trade son espérance de validation devenait négative,
et il échouait aux stress de coût et de délai. SOL ne possédait aucun finaliste.

Le holdout avril–juillet 2026 reste fermé. Il ne pourra être lu que si un finaliste ETH et un
finaliste SOL passent d'abord, séparément, les gates de développement préenregistrés.

## Capture prospective

La capture ajoute un journal indépendant et fail-open pour les trois marchés :

- bookTicker ordonnés par séquence locale, avec bid/ask, tailles, timestamps exchange et updateId ;
- flux aggTrade agrégé par seconde de réception, avec OHLC, volumes acheteur/vendeur, notionnels,
  VWAP et trous d'identifiants ;
- session, source, horloge monotone et gaps explicites ;
- file bornée non bloquante ;
- blocs compressés contrôlés par CRC32 ;
- segments FIFO bornés à 128 × 16 Mio (2 Gio au maximum), avec au plus quatre segments
  temporaires supplémentaires pendant un export épinglé ;
- écritures groupées sur le thread diagnostic afin d’éviter un `fsync` par tick ;
- replay LONG sur bid, SHORT sur ask, entrée au premier quote futur et aucun point futur.

Le ZIP FULL contient `causal_market_stream.jsonl` et `causal_market_manifest.json`. Le manifeste
indique les fichiers sources, le nombre d’enregistrements, les octets et l’état CRC/troncature ; un
snapshot reste immuable pendant l’export puis est libéré avant un éventuel reset diagnostic.

Une panne du recorder, un disque plein ou une file saturée peut perdre une plage explicitement
comptée, mais ne peut ni retarder ni modifier une décision publique.

## Gates avant toute future activation

Un candidat doit notamment fournir, hors échantillon et après frais : au moins 120 trades par
actif, une espérance supérieure à 0,12 R, une borne basse bootstrap positive, un PF d'au moins
1,30, une fréquence utile sur les deux actifs, un drawdown borné, une stabilité mensuelle et une
résistance aux coûts et à la latence. Ensuite seulement vient une collecte forward jamais consultée.

Les résultats historiques sont rétrospectifs et ne garantissent aucun bénéfice futur. Aucune API
privée Binance, aucun ordre automatique et aucune promesse de rentabilité ne sont ajoutés.
