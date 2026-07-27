# Candidate v2.34.0 — Implementation report

## Référence et portée

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche : `agent/v2.32.7-scalp-p01-candidate`
- HEAD de départ verrouillé : `5e00f3f88bf2da5237ae7f8c0d851aa0fb4fe251`
- Base PR : `agent/v2.32.6-candidate`
- Version : `23400` / `2.34.0`
- Nom : **ETH + SOL Scalper Cockpit v2.34.0 — Multi-Market Research**
- Mode : `RESEARCH_ONLY`, `realTradingAllowed=false`, exécution manuelle uniquement.

## Golden master ETH

Une référence immuable, créée avant la généralisation, utilise la graine `23321042` et compare 20 000 snapshots. Le raccourci historique `evaluate(snapshot)` reste le chemin ETH ; `evaluate(snapshot, MarketProfile.eth())` lui délègue directement. Les factories et alias sans symbole restent ETHUSDT. Les décisions, reason codes/textes, scores, quantités, niveaux, signatures, métriques normalisées et tous les champs du plan dynamique sont comparés, avec `Double.doubleToLongBits` pour les doubles.

Les 210 tests v2.33.2.1 ont été conservés sans suppression ni affaiblissement.

## Architecture multi-marchés

- `MarketProfile` centralise les constantes immuables et les politiques de scaling.
- `MarketRegistry` expose une liste ordonnée ETHUSDT/SOLUSDT et rejette tout symbole inconnu.
- `MarketRuntime` isole bougies, aggTrades, quotes, fraîcheur, compteurs, candidats, tombstones, tracker P02, signaux, plan et réarmement.
- `SharedReferenceContext` contient BTCUSDT une seule fois ; BTC n’est jamais enregistré comme marché tradable.
- `MultiMarketCoordinator` applique un verrou de plan et un réarmement indépendants par symbole.
- `MarketPlanOrchestrator` exécute le lifecycle candidat/fill/plan/TP-SL sans dépendance Android.
- `MarketSnapshotFactory` construit causalement un snapshot pour n’importe quel profil enregistré.

Un faux troisième profil de test est enregistré et évalué par le coordinateur sans champ ni branche spécifique dans le service.

## Profil ETH

Le profil `ETH_V23321` reprend directement les constantes v2.33.2.1 : `A_min=0.35`, spread 0.55, SL 0.55–2.50, TP 2.80–5.50, coûts 1.43/2.35, budgets 10.00/14.55, tick 0.01 et uplift historique. Aucun ratio SOL n’est utilisé pour recalculer ETH.

## Profil SOL

Le profil `SOL_V1_20260727` utilise `referencePrice=75.80`, `A_min_reference=0.015`, tick 0.01, quantités entières 1–120 et les budgets de niveau exacts :

- qualité 3 : 10.00 USDT ;
- qualité 4 : 11.14 USDT ;
- qualité 5 : 12.28 USDT ;
- qualité 6 : 13.41 USDT ;
- qualité 7 : 14.55 USDT.

Les minimums/coûts/réserves sont arrondis au tick supérieur, les plafonds au tick inférieur sans jamais passer sous leur minimum. Le sizing SOL est `floor(budget / (stop arrondi + allowance))`, au pas entier, sans uplift ETH et sans réduction silencieuse. Un résultat hors 1–120 ou au-dessus du budget est rejeté.

## Flux, fraîcheur et lifecycle

Le WebSocket combiné est généré depuis le registre et inclut kline/aggTrade/bookTicker pour ETH et SOL, plus kline/bookTicker pour BTC. Les préchargements REST demandent 180 bougies par symbole et les fallbacks/trade IDs sont isolés. Un feed tradé stale bloque uniquement le symbole concerné ; BTC stale bloque les nouvelles créations des deux marchés. Les plans actifs continuent toujours leur surveillance TP/SL.

ETH et SOL peuvent porter simultanément un plan final. Un second plan du même symbole reste interdit. Chaque terminal ne libère et ne réarme que son symbole.

## Persistance et migration

`ActivePlanState` format 2 porte symbole, actif, version de profil, coût/allowance par unité, budget et perte modélisée. Les plans sont stockés sous `plan.<SYMBOL>.*`. Le format historique sans symbole migre vers ETHUSDT et conserve les anciennes quantités 1 ou 2. Une corruption namespacée reste isolée. Les réarmements utilisent `lastTerminalAt.<SYMBOL>` ; l’ancien timestamp migre comme ETH.

La restauration et le reset diagnostic sont silencieux et conservent tous les plans actifs. Seuls TP et SL effacent le plan du symbole concerné.

## Notifications, interface et diagnostics

Chaque nouvelle confirmation finale peut sonner une seule fois. Les signatures et IDs SOL incluent le symbole ; la signature historique ETH est conservée par les overloads historiques. Restauration et terminaux mettent à jour silencieusement le même ID.

L’interface unique affiche ETH, SOL et le contexte BTC, sans sélection de profil. Le status JSON fournit `markets`, `activePlans`, `referenceMarket` et `aggregateRisk`, tout en conservant les alias top-level ETH. L’agrégat de risque est informatif et ne bloque jamais le deuxième symbole. Les ZIP utilisent le préfixe `ETH_SOL_Scalper_Diagnostic_v2_34_0_` et incluent les profils et plans.

## Sécurité et limites

- Aucun ordre automatique, aucune API privée, aucune clé exchange.
- IA informative uniquement après publication.
- RANGE_FADE reste diagnostic-only sur ETH et SOL.
- Une fois publié, un plan est immuable et ne termine que par `TP_TOUCHED` ou `SL_TOUCHED`.
- La validation officielle 1m contrôle prix, distances, volatilité et risque ; elle ne constitue pas un replay exact des flows sous-minute SOL.
- Aucune rentabilité future n’est garantie.
