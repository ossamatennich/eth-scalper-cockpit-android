# NMC — Native Market Cockpit

La version courante est **NMC Stable 6.6** (`2.34.6.6`, `versionCode 23466`).
Le moteur public est désormais `NMC_PROP_DAILY_HYBRID_V4`: un scanner quotidien
reset-safe des 53 cryptos Kraken Prop, alimenté par des klines USD-M mises en
cache et un multiplex `bookTicker` léger. L'ancien `NMC_SCALP_CV_CORE_V1` reste
lisible dans les diagnostics mais ne peut plus publier de nouveau plan.

Chaque plan V4 fige l'actif, le sens, la quantité arrondie vers le bas, l'entrée,
le TP, le SL et l'expiration. L'application reconstruit les interruptions avec
les seules klines 1 minute du plan suivi. Elle n'utilise aucune API privée,
n'envoie aucun ordre et conserve strictement `realTradingAllowed=false`.

Application Android de recherche qui analyse en continu **ETHUSDT** et **SOLUSDT**. **BTCUSDT** sert uniquement de contexte partagé.

La capture microstructure Stable 5.5 reste disponible comme sous-système de diagnostic BTC/ETH/SOL. Elle ne détermine pas les décisions V4.

Les épisodes ETH sont fixés avant les règles à partir des observations RAW et des confirmations P01/P02. Les qualifications d’un même cycle sont arbitrées ensemble avant toute publication. Une seule des trois voies peut gagner selon la priorité figée ; la publication revalide ensuite fraîcheur, unicité du plan, fenêtre d’entrée et risque sans déplacer l’entrée, le TP ou le SL.

## Principes essentiels

- deux plans logiques ouverts maximum et une seule nouvelle décision quotidienne CORE/FALLBACK ;
- les nouveaux plans publics proviennent uniquement de `NMC_PROP_DAILY_HYBRID_V4` ;
- CORE choisit au plus un des cinq actifs liquides après calcul résiduel sur le panel disponible complet ;
- FALLBACK scanne les 53 actifs avec le modèle ExtraTrees figé au 31 décembre 2025 ;
- activation après le reset, entrée figée, expiration avant le reset suivant et deux segments CORE maximum ;
- sizing en quantité exacte, levier Kraken imposé, risque cumulé borné à 2,40 % et arrondi vers le bas ;
- le lifecycle distingue clairement exécutable, ordre limite possible, ordre posé, en cours, trop tard, invalidé et expiré ;
- `realTradingAllowed=false` ;
- l'ancien CV Core, SOL shadow et les captures 5.x restent diagnostiques seulement ;
- la politique `SHADOW_V23447_20260804` et le schéma `SHADOW_SCHEMA_V8` restent silencieux et purement observationnels ;
- les diagnostics shadow/frozen historiques restent observationnels et ne participent jamais à une décision publique V4 ;
- la capture V4 conserve intégralement V3 et utilise le flux incremental isolé officiel `@depth` à cadence par défaut 250 ms, chaque diff brut, et des ancres publiques REST `depth?limit=500` pour ETH/SOL/BTC ;
- une machine d’état par symbole tamponne avant snapshot, synchronise selon `U <= lastUpdateId <= u`, puis impose `pu == previous u`, avec une seule requête REST en vol et backoff borné ;
- un état non ancré produit des diffs bruts mais pas un GAP par message ; seules les transitions de rupture/drop sont enregistrées, avec IDs causaux et compteurs de resynchronisation ;
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
