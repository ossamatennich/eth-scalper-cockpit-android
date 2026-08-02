# NMC — Native Market Cockpit

Application Android de recherche qui analyse en continu **ETHUSDT** et **SOLUSDT**. **BTCUSDT** sert uniquement de contexte partagé.

La version courante est **2.34.4.4** (`versionCode 23444`). L’APK recommandée est l’édition **NMC Stable 4.4**, signée durablement afin que ses mises à jour Android remplacent réellement la version stable précédente. Elle mesure en shadow la qualité et la fréquence utile des confirmations ETH/SOL sans bloquer, publier ou modifier le moindre plan public. Son schéma V5 sépare les qualifications, les occasions réellement exécutables, les ouvertures et les overlaps, y compris après un terminal shadow. Le flux Binance Futures et le fallback REST public restent indépendants du volume des diagnostics. L’application ne passe aucun ordre : l’exécution reste entièrement manuelle.

## Principes essentiels

- un plan actif maximum par marché ;
- ETH et SOL peuvent avoir chacun un plan actif ;
- stop causal déterminé par la structure, la volatilité, l’excursion adverse, le spread et le tick du marché ;
- perte brute entrée–SL limitée à 14,55 USDT, frais affichés séparément ;
- une alerte sonore uniquement lorsqu’un nouveau plan final est confirmé ;
- après publication, le plan est immuable et se termine uniquement au TP ou au SL ;
- `realTradingAllowed=false` ;
- SOL reste un profil de recherche.
- la politique `SHADOW_V23444_20260802` et le schéma `SHADOW_SCHEMA_V5` restent silencieux et purement observationnels.

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
