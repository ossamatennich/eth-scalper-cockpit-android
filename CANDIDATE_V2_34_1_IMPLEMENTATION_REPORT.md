# Candidate NMC v2.34.1.0 — Implementation Report

## Référence et périmètre

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche : `agent/v2.32.7-scalp-p01-candidate`
- HEAD de départ : `e4449e940eb8c03be8f020438bce6e5bc692140f`
- Version Android : `versionCode 23410`, `versionName 2.34.1.0`
- Produit : **NMC — Native Market Cockpit**
- Sous-titre : **Multi-Market Research Engine**
- Mode : `RESEARCH_ONLY`, `realTradingAllowed=false`

Cette intervention ne modifie aucune calibration, formule, constante, décision, entrée, TP, SL, quantité, budget, P01, P02, OLS60, persistance de plan ou réarmement ETH/SOL. Le manifest golden ETH reste byte-identique.

## Identité NMC et interface native

L’application utilise désormais une identité neutre multi-marchés : monogramme géométrique N, trois trajectoires reliées et hexagone ouvert. Les ressources comprennent le launcher adaptatif, sa variante monochrome, l’icône de notification, le splash et le logo intégré. Le label launcher est `NMC`.

La vue HTML historique, son bouton, son état, tous les imports WebView et tout `assets/www` ont été supprimés. L’application expose quatre écrans Android natifs :

1. **Cockpit** : en-tête NMC non compressé, état général, cartes dynamiques issues de `MarketRegistry`, contexte BTC et risque agrégé informatif.
2. **Plans** : plans actifs par symbole et rappel de l’historique borné PLAN_CONFIRMED/RESTORED/TP/SL.
3. **Diagnostic** : santé ETH/SOL/BTC, index du recorder, cinq derniers événements, détails repliables et export ZIP.
4. **Outils** : IA informative, tests locaux et reset diagnostic confirmé.

Les `WindowInsets` couvrent barres système, découpe et clavier. Les cibles tactiles font au moins 48 dp. Les cartes de marché sont construites une fois depuis le registre et les `TextView` ne sont actualisées que si leur valeur change ; l’onglet et le scroll sont conservés.

## Runtime diagnostic optimisé

`PersistentRecorderIndex` maintient incrémentalement compteurs, plage temporelle, tailles, symboles, types d’événements, trades confirmés, restaurations et terminaux. Il est sauvegardé atomiquement. Une reconstruction complète n’est permise qu’au démarrage lorsque l’index est absent ou invalide. `broadcastStatus()` utilise uniquement l’index et les runtimes en mémoire : zéro lecture JSONL dans le chemin chaud.

`DiagnosticEventCoalescer` agrège uniquement les événements répétitifs (`RAW_DECISION`, `ENGINE_DIAGNOSTIC`, `ADMISSION_REJECTED`, stale et reason codes répétitifs). Le premier événement est immédiat, puis un résumé au plus toutes les cinq minutes. Tous les événements lifecycle restent non coalescés.

`FeedObservabilityTracker` émet uniquement lors d’une transition FRESH/STALE ou WEBSOCKET/REST_FALLBACK. Il enregistre les âges, compteurs, réseau, état foreground et exemption d’optimisation batterie sans permission intrusive.

Les frames génériques sont persistées séparément pour ETHUSDT et SOLUSDT, au plus toutes les cinq secondes par symbole. Une frame n’entre jamais dans le journal d’événements.

## Export ZIP en streaming

`DiagnosticStreamingExporter` écrit directement dans le flux MediaStore ou fichier. Il ne construit ni le ZIP ni les JSONL/CSV complets en mémoire. Les fichiers `.1` puis courants sont lus ligne par ligne avec un tampon maximal de 8 192 octets. Une erreur supprime la destination partielle. L’interface affiche préparation, progression, succès ou erreur et désactive le bouton pendant l’export.

Contenu canonique exact :

- `status.json`
- `markets.json`
- `active_plans.json`
- `profiles_manifest.json`
- `market_events.jsonl`
- `market_frames.jsonl`
- `market_candidates.jsonl`
- `market_candidates.csv`
- `market_plans.jsonl`
- `market_plans.csv`
- `market_summary.json`
- `market_summary.txt`
- `feed_health.json`
- `health_check.txt`
- `instructions.txt`
- `export_manifest.json`

Le manifest d’export fournit taille non compressée et SHA-256 pour chaque entrée antérieure au manifest, ainsi que version, date, ordre des sources et symboles configurés. Les datasets dérivés ont une identité explicite afin d’éviter les entrées strictement identiques, y compris lorsqu’ils sont vides.

## Reset et invariants

Le reset confirmé efface les journaux courants et `.1`, l’index et le coalescer, puis réinsère silencieusement les plans actifs. Les plans, réarmements, notification IDs, entrées, TP, SL et quantités restent inchangés. Après publication, seuls `TP_TOUCHED` et `SL_TOUCHED` terminent un plan.

## Axes de recherche conservés

Les observations du premier diagnostic naturel sont documentées séparément. Aucune règle n’a été créée à partir d’une session unique. En particulier, aucune modification n’a été appliquée au veto ETH, à `PRIX_DEJA_TROP_LOIN`, aux seuils SOL, au sizing ou aux niveaux TP/SL.

## Limites

- L’outil reste un moteur de recherche et d’aide à l’exécution manuelle, sans ordre automatique.
- L’export est conçu pour un faible pic mémoire ; la mesure reproductible porte sur un journal réel de 64 Mio et un tampon borné, pas sur un profilage matériel de tous les appareils Android.
- Les métadonnées finales GitHub Actions sont inscrites dans la description de la PR après le run afin d’éviter un second commit documentaire.
