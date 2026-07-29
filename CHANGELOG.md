# Changelog

## 2.34.3.2 — flux publics résilients

- rotation automatique entre plusieurs routes publiques de données de marché ;
- secours REST pour ETH, SOL et BTC lorsque le WebSocket principal est indisponible ;
- reprise immédiate au retour du réseau et nouvelle tentative de préchargement ;
- maintien renforcé du service de surveillance en arrière-plan ;
- source active et dernier incident réseau visibles dans l'écran Diagnostic ;
- aucune modification des règles de marché, du sizing ou du lifecycle TP/SL.

## 2.34.3.1 — stop dynamique et risque brut multi-marchés

- invalidation causale fondée sur la structure récente, la volatilité, l’excursion adverse, le spread et le tick ;
- suppression des planchers et plafonds absolus du chemin public du stop ;
- quantité calculée après le stop avec une perte brute maximale de 14,55 USDT hors frais ;
- frais et perte totale estimée conservés séparément dans l’interface et les diagnostics ;
- adaptation indépendante par `MarketProfile` pour ETH, SOL et les futurs marchés ;
- suppression du replay historique et de ses fixtures, manifestes et étapes CI devenus inutiles.

## 2.34.3.0 — version consolidée

- runtime extensible ETH et SOL avec contexte BTC partagé ;
- confirmation P01/P02 causale et silencieuse avant publication ;
- plans actifs persistants, immuables et terminés uniquement au TP ou au SL ;
- interface native NMC et diagnostics multi-marchés bornés ;
- alerte sonore centrale unique pour chaque nouveau plan final.
