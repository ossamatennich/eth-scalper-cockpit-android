# Changelog

## 2.34.3.4 — flux indépendant du stockage diagnostic

- connexion Binance Futures et préchargement REST lancés avant toute publication diagnostic ;
- ancien index recorder totalement ignoré dans le chemin de démarrage ;
- écritures JSONL et sauvegardes d’index déplacées sur un thread dédié ;
- reset et export attendent explicitement la fin des écritures diagnostics ;
- aucune modification des règles de marché, des plans ou du lifecycle TP/SL.

## 2.34.3.3 — démarrage Android non bloquant

- service foreground activé avant toute initialisation potentiellement lente ;
- suppression du scan et de la migration complète des journaux dans le chemin de démarrage ;
- chargement rapide de l'index recorder sans lecture des JSONL ;
- conservation intégrale des journaux et des plans existants ;
- Binance Futures reste la source principale et prioritaire.

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
