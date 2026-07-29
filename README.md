# NMC — Native Market Cockpit

Application Android de recherche qui analyse en continu **ETHUSDT** et **SOLUSDT**. **BTCUSDT** sert uniquement de contexte partagé.

La version courante est **2.34.3.0** (`versionCode 23430`). L’application ne passe aucun ordre : l’exécution reste entièrement manuelle.

## Principes essentiels

- un plan actif maximum par marché ;
- ETH et SOL peuvent avoir chacun un plan actif ;
- une alerte sonore uniquement lorsqu’un nouveau plan final est confirmé ;
- après publication, le plan est immuable et se termine uniquement au TP ou au SL ;
- `realTradingAllowed=false` ;
- SOL reste un profil de recherche.

## Construire et tester

Prérequis : JDK 17 et Android SDK 35.

```bash
gradle testDebugUnitTest
gradle test
python3 -m unittest tools/test_validate_sol_profile.py
python3 tools/validate_eth_v2331_replay.py
gradle assembleDebug
gradle assembleRelease
gradle lintRelease
```

APK debug : `app/build/outputs/apk/debug/app-debug.apk`.

## Organisation

- `app/` : application Android et tests JVM ;
- `tools/` : validateurs reproductibles et fixtures compactes ;
- `docs/` : architecture, validation et diagnostics ;
- `.github/workflows/nmc-ci.yml` : unique pipeline CI actif.

Documentation :

- [Architecture](docs/ARCHITECTURE.md)
- [Validation du moteur](docs/ENGINE_VALIDATION.md)
- [Diagnostics](docs/DIAGNOSTICS.md)
- [Rapports v2.34.3.0](docs/releases/v2.34.3.0/)

Les résultats historiques servent à contrôler la stabilité du moteur. Ils ne garantissent aucun résultat financier futur.
