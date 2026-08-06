# NMC — Native Market Cockpit

Application Android de recherche qui analyse en continu **ETHUSDT** et **SOLUSDT**. **BTCUSDT** sert uniquement de contexte partagé.

La version courante est **2.34.4.9** (`versionCode 23449`). L’APK recommandée est **NMC Stable 4.9 — CV Core V1**, signée durablement. `NMC_SCALP_CV_CORE_V1` est l’unique source des nouveaux plans finaux manuels ETH. Les décisions et confirmations historiques restent diagnostiquées, mais ne publient plus de nouveaux plans ETH ou SOL. Un plan actif restauré conserve exactement ses niveaux jusqu’à son TP ou SL. L’application ne passe aucun ordre : l’exécution reste entièrement manuelle.

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
- `realTradingAllowed=false` et aucune API Binance privée n’est utilisée.

## Construire et tester

Prérequis : JDK 17 et Android SDK 35.

```bash
gradle testDebugUnitTest
gradle test
python3 -m unittest tools/test_validate_sol_profile.py
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
