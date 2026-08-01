# Changelog

## 2.34.4.1 — durcissement de l’observabilité shadow

- isolation de toutes les exceptions shadow afin qu’elles ne puissent jamais interrompre le moteur public ;
- terminaux shadow acceptés uniquement sur une cotation tradée fraîche et valide ;
- schéma `SHADOW_SCHEMA_V2` avec `E60` absolu et `eNormalized` séparés ;
- `resultR` calculé sur le risque net planifié, frais inclus ;
- protection causale contre une cible déjà touchée avant l’ouverture shadow ;
- tests fonctionnels renforcés sans modification des seuils ni des plans publics.

## 2.34.4.0 — calibration A/B shadow isolée

- garde P01 finale et anti-épuisement P02 mesurés sans modifier les confirmations publiques ;
- deux voies de recherche shadow causales, dédupliquées et silencieuses ;
- suivi shadow immuable jusqu’au TP ou au SL, indépendant des compteurs et plans publics ;
- sonde de sizing tenant compte des frais, sans modifier la quantité active ;
- événements shadow typés et exportables sous la politique `SHADOW_V23440_20260801` ;
- aucune modification des filtres, timings, TP, SL, quantités, alertes ou lifecycles publics.

## 2.34.3.9 — statut, export et diagnostics fiabilisés

- normalisation JSON récursive des nombres non finis, maps, listes, tableaux et valeurs Java inconnues ;
- conservation stricte du dernier statut valide et statut minimal riche en cas d’échec d’une section optionnelle ;
- export déclenché uniquement après acquittement du flush, avec snapshot unique et traçabilité SHA-256 ;
- coalescence des diagnostics répétitifs sur des champs stables, sans perdre les événements métier ;
- observabilité complète du canal sonore, de la ressource, du volume et des restrictions Android, sans modifier les réglages ;
- aucune modification du moteur, des entrées, TP, SL, quantités ou lifecycles.

## 2.34.3.8 — diagnostic complet et alerte finale vérifiée

- index recorder, événements récents, plans actifs et santé du canal conservés dans le statut de secours ;
- erreurs des sections de recherche isolées sans faire tomber tout le statut Android ;
- détails techniques affichant explicitement le mode complet ou de secours et sa cause ;
- nouveau canal sonore `nmc_final_signal_loud_v2`, sans héritage des réglages persistants du canal v1 ;
- une notification n’est plus marquée comme alertée si Android signale un canal désactivé, faible ou sans son ;
- trois nouvelles tentatives bornées d’alerte pour un nouveau plan lorsque le canal devient disponible ;
- restaurations, TP et SL toujours silencieux ; aucune règle de marché modifiée.

## 2.34.3.7 — flux découplé du statut et des diagnostics

- Binance Futures public reste la source principale et prioritaire ;
- sérialisation complète des 7 200 frames supprimée du chemin d’analyse temps réel ;
- statut Android construit à partir de résumés incrémentaux et de 20 événements récents au maximum ;
- mutations REST, ingestion WebSocket, évaluation et statut sérialisés de façon sûre ;
- statut minimal de secours publié si une donnée diagnostic détaillée est invalide ;
- édition installable clairement nommée `NMC Stable 3.7` afin de la distinguer d’une ancienne installation parallèle ;
- aucune modification des signaux, entrées, TP, SL, quantités ou règles de marché.

## 2.34.3.6 — démarrage automatique des flux

- démarrage du service avant les dialogues de permission Android ;
- reprise automatique bornée tant qu’aucun feed complet et frais n’est disponible ;
- nouvelle tentative après la réponse à la permission de notification et au retour dans l’application ;
- récupération REST publique immédiate au démarrage, sans attendre le premier contrôle de santé ;
- le bouton de réinitialisation diagnostic n’est plus nécessaire pour amorcer les flux ;
- aucune modification de la logique des signaux ou des plans.

## 2.34.3.5 — livraison Android stable

- nouvelle APK `NMC Stable` installable sans conflit avec les anciennes APK debug ;
- signature Android durable injectée par les secrets GitHub Actions et vérifiée en CI ;
- package stable indépendant `com.ethscalper.cockpit.stable` pour la migration initiale ;
- conservation intégrale du démarrage réseau non bloquant de la v2.34.3.4 ;
- aucune modification des règles de marché, des plans ou du mode `RESEARCH_ONLY`.

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
