# NMC — Native Market Cockpit

Application Android de recherche qui analyse en continu **ETHUSDT** et **SOLUSDT**. **BTCUSDT** sert uniquement de contexte partagé.

La version courante est **2.34.5.3** (`versionCode 23453`). L’APK recommandée est **NMC Stable 5.3 — Forced Liquidation Capture V3**, signée durablement. `NMC_SCALP_CV_CORE_V1` reste l’unique source des nouveaux plans finaux manuels ETH et n’est pas recalibré dans cette version. L’application ne passe aucun ordre : l’exécution reste entièrement manuelle.

Les épisodes ETH sont fixés avant les règles à partir des observations RAW et des confirmations P01/P02. Les qualifications d’un même cycle sont arbitrées ensemble avant toute publication. Une seule des trois voies peut gagner selon la priorité figée ; la publication revalide ensuite fraîcheur, unicité du plan, fenêtre d’entrée et risque sans déplacer l’entrée, le TP ou le SL.

## Principes essentiels

- un seul plan public actif maximum ;
- les nouveaux plans finaux CV Core concernent uniquement ETH ; SOL reste observé comme contexte causal ;
- trois voies figées : Dual Exhaustion SHORT, Capitulation LONG et P02 Balanced SHORT ;
- risque frais inclus limité à 14,55 USDT pour les voies RAW et à 7,275 USDT pour la voie P02 ;
- une alerte sonore uniquement lorsqu’un nouveau plan CV Core est persisté ;
- entrée affichée valable cinq secondes, sans déplacement ultérieur des niveaux ;
- après publication, le plan est immuable et se termine uniquement au TP ou au SL ;
- `realTradingAllowed=false` ;
- SOL reste un profil de recherche et de contexte ;
- la politique `SHADOW_V23447_20260804` et le schéma `SHADOW_SCHEMA_V8` restent silencieux et purement observationnels ;
- les diagnostics shadow/frozen historiques restent observationnels et ne participent jamais à une décision publique CV Core ;
- la capture V3 coalesce le top-of-book à 250 ms, agrège les `aggTrade` en buckets causaux de 100 ms, conserve `depth20@100ms` à 250 ms et stocke chaque snapshot `forceOrder` validé pour ETH/SOL/BTC ;
- une socket `PUBLIC_WS` (`/public/stream`) transporte uniquement `bookTicker` et `depth20`, tandis qu’une socket `MARKET_WS` (`/market/stream`) transporte `aggTrade`, `kline_1m` et `forceOrder` ; le silence naturellement rare de `forceOrder` n’affecte jamais la santé de capture ;
- le laboratoire `tools/scalp_research.py` isole physiquement le holdout et rejette les candidats qui ne résistent pas aux frais, à la latence et aux tests hors échantillon ;
- `realTradingAllowed=false` et aucune API Binance privée n’est utilisée.

## Construire et tester

Prérequis : JDK 17 et Android SDK 35.

```bash
python3 -m pip install -r tools/research-requirements.txt
gradle testDebugUnitTest
gradle test
python3 -m unittest tools/test_validate_sol_profile.py tools/test_scalp_research.py tools/test_microstructure_research.py
gradle assembleDebug
gradle assembleRelease
gradle lintRelease
```

APK debug : `app/build/outputs/apk/debug/app-debug.apk`.

## Organisation

- `app/` : application Android et tests JVM ;
- `tools/` : validation du profil SOL ;
- `docs/` : architecture, validation et diagnostics ;
- `.github/workflows/nmc-ci.yml` : pipeline CI actif.

Documentation : [architecture](docs/ARCHITECTURE.md), [validation](docs/ENGINE_VALIDATION.md), [diagnostics](docs/DIAGNOSTICS.md) et [rapports v2.34.3.1](docs/releases/v2.34.3.1/).

Les diagnostics servent à améliorer les versions de recherche. Ils ne garantissent aucun résultat financier futur.
