# Changelog

## 2.34.6.6 — NMC Stable 6.6 / runtime V4 uniquement

- Remplace le service foreground historique par `V4ForegroundService`, hôte unique de `V4RuntimeCoordinator`.
- Retire `MainActivity` et `MarketWatchService` du manifeste de production sans supprimer les sources historiques.
- Ajoute le canal silencieux `nmc_v4_monitor_v1`, alimenté par le statut V4 réel et ouvrant exclusivement `V4MainActivity`.
- Annule la notification historique `22801` et retire le canal `eth_scalper_watch_v22801` lors de la migration.
- Conserve sans changement le canal fort `nmc_final_signal_loud_v2`, son son, sa vibration et sa déduplication persistante.

## 2.34.6.5 — NMC Stable 6.5 / finition opérationnelle et alerte forte

- Réutilise le canal sonore NMC `nmc_final_signal_loud_v2`, son personnalisé et vibration longue pour les événements V4 actionnables, entrée exécutée, TP et SL.
- Déduplique durablement chaque alerte par identifiant de plan et événement de lifecycle.
- Ajoute la copie exacte de la quantité et sépare visuellement LONG/SHORT du symbole.
- Clarifie les badges `ORDRE POSÉ`, `EN COURS`, `TP ATTEINT` et `SL ATTEINT` sans modifier le lifecycle de trading.
- Conserve strictement `NMC_PROP_DAILY_HYBRID_V4`, le modèle, l'alpha, le sizing et `realTradingAllowed=false`.

## 2.34.6.4 — NMC Stable 6.4 / finition UI mobile

- Stabilise le scroll en évitant la reconstruction des écrans lors des simples ticks runtime.
- Remplace la navigation basse par des items compacts avec icônes Accueil, Plans et Historique, hors de la safe area système.
- Compacte les cartes de plan, les métadonnées et les actions sans retirer d'information opérationnelle.
- Affiche ENTRY, TP et SL avec une précision visuelle adaptée, sans troncature, tout en copiant la valeur moteur complète.
- Conserve strictement `NMC_PROP_DAILY_HYBRID_V4`, le modèle, l'alpha, le sizing et `realTradingAllowed=false`.

## 2.34.6.3 — NMC Stable 6.3 / UI-UX final

- Protège l'en-tête et la navigation basse avec les insets système Android.
- Rend Accueil et Plans entièrement scrollables, y compris avec plusieurs plans.
- Ajoute la copie exacte de ENTRY, TP et SL avec retour discret « Copié ».
- Relie ACTIF / SYNCHRO / HORS LIGNE au réseau, au socket Binance, à la fraîcheur bookTicker et à la dernière analyse réelle.
- Modernise uniquement l'interface native sans modifier `NMC_PROP_DAILY_HYBRID_V4`, le modèle, le sizing ou les règles de trading.

## 2.34.6.2 — NMC Stable 6.2 / Android launch hotfix

- aligne `V4MainActivity` sur le thème Android natif `Theme.Material.NoActionBar` en utilisant `android.app.Activity` ;
- ne modifie ni le moteur `NMC_PROP_DAILY_HYBRID_V4`, ni son modèle, ni ses règles, ni son UX.

## 2.34.6.1 — NMC Stable 6.1 / V4 operational corrections

- rend l'historique de confiance FALLBACK idempotent et persistant par date UTC ;
- applique fill puis STOP lorsqu'un ordre posé touche ENTRY et SL dans la même bougie 1m ;
- protège les quantités engagées, réserve le risque restant et sépare les gardes fresh/continuation ;
- aligne tout seed cross-sectionnel sur une date UTC commune, sans réutiliser une ligne J-1.

## 2.34.6.0 — NMC Stable 6.0 / NMC_PROP_DAILY_HYBRID_V4

- remplace la publication publique CV Core par le moteur quotidien V4 reset-safe, sans supprimer les diagnostics historiques ;
- ajoute le registre central des 53 cryptos et des leviers Kraken Prop imposés, sans sélecteur de levier ;
- ajoute les klines quotidiennes USD-M validées/mises en cache, un flux `bookTicker` léger et la reconstruction 1 minute réservée aux plans suivis ;
- fige le CORE residual momentum et les deux ExtraTrees FALLBACK entraînés uniquement sur 2023–2025 (`207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680`) ;
- ajoute le sizing quantitatif borné, les métadonnées exchangeInfo, le journal d'équité local et le lifecycle persistant complet ;
- remplace l'accueil historique par une UX Accueil / Plans / Historique dédiée à l'exécution manuelle ;
- conserve `realTradingAllowed=false`, n'utilise aucune API privée et ne place aucun ordre.

## 2.34.5.5 — Incremental Depth Stability + Storage Fix

- remplace `@depth@100ms` par le flux USD-M officiel `@depth` à cadence par défaut 250 ms, sans toucher aux flux 5.3 ni aux diffs bruts ;
- applique une machine d’état bornée par symbole : buffer avant snapshot, couverture `U/u`, chaînage `pu`, requête unique en vol et retry/backoff contrôlé ;
- supprime le storm REST et le GAP `DEPTH_DIFF_UNANCHORED` par message ; une rupture réelle produit une seule invalidation puis une resynchronisation ;
- utilise le snapshot public officiel de 500 niveaux et expose cadence observée, volume/h et estimation de rétention dans le manifeste ;
- ajoute la télémétrie parser/raw/pu mismatch/drop/resync et des fixtures longues sans réseau ;
- conserve intégralement `NMC_SCALP_CV_CORE_V1` et `realTradingAllowed=false`.

## 2.34.5.3 — Forced Liquidation Capture V3

- ajoute `ethusdt@forceOrder`, `solusdt@forceOrder` et `btcusdt@forceOrder` exclusivement à `MARKET_WS` ;
- introduit `NMC_CAUSAL_MARKET_CAPTURE_V3` et le kind `LIQUIDATION_SNAPSHOT`, avec validation stricte, stockage borné, CRC et replay V1/V2/V3 ;
- expose les compteurs par symbole et dans le manifeste FULL sans faire du silence naturel de ce stream une condition de santé ;
- met à jour l’outil offline pour charger V1/V2/V3 et compter les snapshots sans créer de règle de stratégie ;
- conserve intégralement `NMC_SCALP_CV_CORE_V1` et `realTradingAllowed=false`.

## 2.34.5.2 — Market/Public WebSocket Namespace Fix

- sépare les flux Binance USD-M Futures entre `PUBLIC_WS` (`bookTicker`, `depth20@100ms`) via `/public/stream` et `MARKET_WS` (`aggTrade`, `kline_1m`) via `/market/stream` pour ETH/SOL/BTC ;
- interdit par routage testable qu’une famille de messages soit acceptée sur la mauvaise socket ;
- exige un `aggTrade` MARKET_WS récent sur les trois symboles pour rendre une capture utilisable : le fallback REST seul reste dégradé ;
- expose connexions, reconnexions, échecs et fermetures bornées avec endpoint, code/reason, statut handshake, exception et âge du dernier message ;
- conserve Capture V2, queue/writer, CRC, REST gap-fill et le moteur public `NMC_SCALP_CV_CORE_V1` sans aucune recalibration ; `realTradingAllowed=false`.

## 2.34.5.1 — Reliable Microstructure Capture V2

- découple la recherche microstructure sur une socket Futures publique `aggTrade` + `depth20@100ms`, sans changer le flux de décision CV Core ;
- capture les trades agressifs en buckets de réception locale de 100 ms avec convention maker documentée, déduplication WS/REST, gaps d’IDs et provenance ;
- conserve les niveaux bruts bid/ask top 20 à cadence bornée de 250 ms et coalesce aussi le top-of-book avant la file ;
- remplace l’attente writer de deux secondes par un drain signalé à haute pression et une latence maximale de 75 ms, avec pertes explicites par type ;
- expose la santé indépendante de chaque stream/symbole, le manifeste V2 et l’outil offline `microstructure_research.py` sans activer de nouvelle règle ;
- maintient `NMC_SCALP_CV_CORE_V1`, `realTradingAllowed=false`, les alertes et le lifecycle public strictement inchangés.

## 2.34.5.0 — capture causale prospective et laboratoire anti-surapprentissage

- conserve strictement le moteur public CV Core 4.9 : aucun candidat ETH/SOL n’a franchi les gates de robustesse, donc aucun seuil, signal, plan, sizing, TP/SL ou alerte n’est modifié ;
- ajoute une capture fail-open des `bookTicker` et `aggTrade` publics Binance Futures pour ETHUSDT, SOLUSDT et BTCUSDT, ordonnée par temps de réception local ;
- agrège les trades par seconde avec OHLC, flow acheteur/vendeur, notionnel, VWAP, trous d’identifiants, sessions et gaps explicites ;
- stocke des blocs compressés CRC32 dans une rétention FIFO bornée, avec file non bloquante et export ZIP en streaming ;
- ajoute un replay strict bid/ask sans point futur ainsi qu’un laboratoire reproductible qui garde le holdout avril–juillet 2026 fermé tant qu’ETH et SOL n’ont pas chacun un finaliste robuste ;
- documente le rejet des meilleurs backtests fragiles après frais/latence, sans promesse de rentabilité, et maintient `realTradingAllowed=false`.

## 2.34.4.9 — CV Core V1, moteur public unique

- correctif d’audit : un plan CV Core produit désormais exactement une ouverture économique et un seul terminal TP ou SL dans le recorder, l’index persistant et l’export FULL ;
- ajoute des clés d’idempotence bornées et persistées `OPEN|engineId|signature` et `TERMINAL|engineId|signature|terminalStatus`, sans modifier les épisodes de trading ;
- aligne `confirmedTrades`, `tp`, `sl`, le résumé CV Core et `market_summary.txt` sur le nombre réel de plans économiques ;
- remplace le moteur public 4.8 par `NMC_SCALP_CV_CORE_V1`, toujours actif et limité à trois voies ETH figées ;
- fixe les épisodes de mouvement avant toute règle, avec déduplication causale de 180 secondes et sans cooldown terminal ;
- calcule les rendements et efficacités directionnels ETH/SOL sur des historiques une seconde bornés, sans point futur ;
- réserve 14,55 USDT de risque frais inclus aux voies RAW et exactement 7,275 USDT à la confirmation P02 ;
- supprime le mode utilisateur, les cinq anciennes voies et le comparateur legacy, tout en conservant la migration d’un plan 4.8 déjà actif ;
- maintient la suppression des nouvelles publications legacy ETH/SOL, les diagnostics complets et `realTradingAllowed=false`.

## 2.34.4.8 — Scalp Action V1 manuel

- corrige l’arbitrage global inter-source : toutes les confirmations legacy et la décision RAW d’un même cycle sont comparées avant l’unique publication ;
- revalide les flux, le plan actif, la fenêtre de cinq secondes, l’économie et le budget juste avant la persistance, avec un code de refus précis ;
- affiche pour Scalp Action la perte totale frais inclus et le budget exact de 14,55 USDT, sans modifier le rendu des plans legacy restaurés ;
- complète le résumé live avec temps frais, profit factor, expectancy, drawdown, frais et fréquence ;
- conserve les métriques de confirmation au timestamp causal et ajoute les événements explicites de non-sélection, doublon et comparateur occupé ;
- ajoute `NMC_SCALP_ACTION_V1`, moteur public ETH indépendant à cinq voies figées ;
- observe causalement les décisions brutes et les confirmations legacy, avec contexte ETH/SOL/BTC borné ;
- calcule l’entrée au bid/ask exécutable, des niveaux conservateurs et une quantité frais inclus sous 14,55 USDT ;
- neutralise les nouvelles publications legacy ETH/SOL tout en conservant diagnostics, frozen, shadow et comparateurs silencieux ;
- restaure sans conversion les plans déjà actifs, ajoute une fenêtre d’entrée de cinq secondes et un rollback local persistant ;
- reste manuel uniquement, sans ordre automatique, avec `realTradingAllowed=false`.

## 2.34.4.7 — protocole frozen de calibration de rentabilité

- ajoute un portefeuille shadow indépendant : ETH Range haute volatilité et deux branches SOL Accel38 simultanées ;
- fige les seuils, les multiples de A, la signature structurelle et les buckets de sensibilité pour le futur holdout ;
- applique un sizing frais inclus sous 14,55 USDT et remplit les terminaux exactement aux TP/SL planifiés ;
- expose un résumé borné distinct sans importer les résultats historiques dans les compteurs futurs ;
- politique `SHADOW_V23447_20260804`, schéma `SHADOW_SCHEMA_V8`, protocole public interdit, moteur public inchangé.

## 2.34.4.6 — intégrité des télémétries shadow

- calcule Range Reclaim depuis `movementExtreme`, avec `movementOrigin` conservé uniquement comme diagnostic séparé ;
- attribue No-Retrace au composant officiel `ETH_NO_RETRACE_BREAKOUT_RESEARCH` ;
- remet à zéro l’horloge Reacceleration dès qu’un plan shadow est actif ;
- expose des snapshots FIFO bornés à 160 mouvements avec compteurs de doublons consolidés ;
- politique `SHADOW_V23446_20260803`, schéma `SHADOW_SCHEMA_V7`, moteur public inchangé.

## 2.34.4.5 — recherche shadow réaccélération et fréquence protégée

- remplace le baseline universel SOL P01 par un garde shadow propre à SOL, sans effet sur les confirmations publiques ;
- ajoute `ETH_FLOW_REACCELERATION_V2`, avec deux branches de flow et dix secondes de stabilité continue avant toute ouverture shadow ;
- conserve l’ancienne continuation ETH comme comparateur sans ouverture et place Range Fade en quarantaine ;
- ajoute les télémétries bornées Range Reclaim et No-Retrace Breakout, sans plan, alerte ni faux terminal ;
- étend le résumé expérimental V6 et conserve le sizing shadow frais inclus ;
- politique `SHADOW_V23445_20260803`, schéma `SHADOW_SCHEMA_V6`, moteur public inchangé.

## 2.34.4.4 — comptabilité shadow qualité/fréquence corrigée

- qualifications, occasions exécutables et ouvertures comptées séparément, sans double incrément ;
- unions exactes public/shadow et agrégat `ALL` ETH+SOL ;
- registre FIFO borné à 256 plans permettant les overlaps après TP ou SL shadow ;
- quantité des lanes shadow réellement calculée avec frais, pas, minimum, maximum et plafond qualité ;
- résumé et terminaux publics entièrement isolés en fail-open ;
- politique `SHADOW_V23444_20260802`, schéma `SHADOW_SCHEMA_V5`, moteur public inchangé.

## 2.34.4.3 — architecture shadow qualité + fréquence

- garde P01 symbolique : strict sur ETH, baseline publique conservée sur SOL ;
- reprise P01 précoce SOL réutilisant le sélecteur et la stabilité existants ;
- voie ETH de continuation à flow confirmé et voie secondaire RANGE_FADE LONG, ré-ancrées causalement ;
- déduplication par composant et par mouvement, overlaps public/shadow explicitement mesurés ;
- résumé incrémental borné de qualité, résultats nets et fréquence dans le statut et l’export ;
- politique `SHADOW_V23443_20260802`, schéma `SHADOW_SCHEMA_V4`, aucune activation publique.

## 2.34.4.2 — bridge shadow ETH et politiques symboliques

- pont fail-open entre le moteur ETH historique et la couche d’observation shadow commune à SOL ;
- une décision A/B et une sonde de sizing possibles pour chaque confirmation publique ETH ou SOL ;
- quarantaine shadow des P02 SOL et score shadow minimal de 85 pour les P02 ETH, sans veto public ;
- nouvelle voie de recherche silencieuse `ETH_FLOW_EXPANSION_EXTENDED`, avec mesure de latence et déduplication ;
- télémétrie bornée des mouvements ETH BTC-led manqués, sans ouverture de plan ;
- politique `SHADOW_V23442_20260802`, schéma `SHADOW_SCHEMA_V3`, aucun changement du moteur public.

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
## 2.34.5.4 — Incremental Depth Capture V4

- ajoute un troisième WebSocket Futures public isolé avec `ethusdt@depth@100ms`, `solusdt@depth@100ms` et `btcusdt@depth@100ms` ;
- ajoute les records causaux bruts `DEPTH_DIFF` et les ancres REST publiques `DEPTH_BOOTSTRAP` (`limit=1000`) dans `NMC_CAUSAL_MARKET_CAPTURE_V4` ;
- vérifie la continuité officielle `U/u/pu`, invalide explicitement les intervalles perdus et ré-ancre sans bloquer les flux 5.3 ;
- préserve le replay V1/V2/V3, ajoute V4 au manifest et à l’outil offline, avec une santé incremental-depth distincte ;
- release collector-only : moteur `NMC_SCALP_CV_CORE_V1`, règles, risques, publication et notifications inchangés ; `realTradingAllowed=false`.
