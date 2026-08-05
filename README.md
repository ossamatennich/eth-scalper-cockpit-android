# NMC — Native Market Cockpit

Application Android de recherche qui analyse en continu **ETHUSDT** et **SOLUSDT**. **BTCUSDT** sert uniquement de contexte partagé.

La version courante est **2.34.4.8** (`versionCode 23448`). L’APK recommandée est **NMC Stable 4.8 — Scalp Action V1**, signée durablement. `NMC_SCALP_ACTION_V1` devient l’unique source des nouveaux plans finaux manuels ETH. L’ancien moteur continue ses calculs et diagnostics dans un comparateur silencieux, sans publier de nouveaux plans ETH ou SOL. Les plans actifs restaurés d’une version antérieure restent inchangés jusqu’à leur TP ou SL. L’application ne passe aucun ordre : l’exécution reste entièrement manuelle.

## Principes essentiels

- un seul plan public actif maximum ;
- les nouveaux plans finaux Scalp Action concernent uniquement ETH ; SOL reste observé comme contexte ;
- stop causal déterminé par la structure, la volatilité, l’excursion adverse, le spread et le tick du marché ;
- perte brute entrée–SL limitée à 14,55 USDT, frais affichés séparément ;
- une alerte sonore uniquement lorsqu’un nouveau plan Scalp Action est persisté ;
- entrée affichée valable cinq secondes, sans déplacement ultérieur des niveaux ;
- rollback local immédiat `ACTION_ON` / `DIAGNOSTICS_ONLY` dans Outils ;
- après publication, le plan est immuable et se termine uniquement au TP ou au SL ;
- `realTradingAllowed=false` ;
- SOL reste un profil de recherche et de contexte ;
- la politique `SHADOW_V23447_20260804` et le schéma `SHADOW_SCHEMA_V8` restent silencieux et purement observationnels ;
- le protocole frozen est `futureHoldoutOnly=true`, `publicActivationAllowed=false` et `automaticPromotionAllowed=false`.
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
