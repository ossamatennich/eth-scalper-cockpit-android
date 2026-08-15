# NMC v2.34.4.2 — rapport du bridge shadow ETH

## Défaut corrigé

La v2.34.4.1 conservait correctement ETH sur son moteur historique, mais la couche shadow était appelée uniquement par `MarketPlanOrchestrator`, utilisé pour SOL. Les confirmations ETH ne produisaient donc aucune comparaison A/B.

## Correction

`LegacyEthShadowBridge` adapte les candidats et confirmations ETH réels vers `ShadowObservationEngine`, couche pure également utilisée par l’orchestrateur générique. Le `continue` ETH de `evaluateSecondaryMarkets()` reste en place : ETH ne passe jamais dans le moteur public secondaire. Toute exception shadow est isolée et le recorder d’erreur est lui-même protégé.

La politique `SHADOW_V23442_20260802` / `SHADOW_SCHEMA_V3` ajoute, uniquement en observation :

- quarantaine systématique des confirmations P02 SOL ;
- score minimal shadow de 85 puis anti-épuisement pour P02 ETH ;
- voie `ETH_FLOW_EXPANSION_EXTENDED`, après les voies pullback et mid-vol existantes ;
- mesure `qualificationAt`, `firstExecutableAt`, `shadowOpenedAt`, `executableDelayMs` ;
- observation bornée `ETH_BTC_LED_BREAKOUT_RESEARCH` lorsqu’une cible ETH est atteinte avant confirmation.

Les plans publics, alertes, quantités, entrées, TP, SL, persistance, réarmement et terminaux restent inchangés. Les plans shadow sont silencieux, immuables et terminent uniquement au TP ou au SL sur cotation fraîche. `realTradingAllowed=false` demeure obligatoire. Cette recherche ne garantit aucune rentabilité ; une validation Samsung et des diagnostics hors échantillon restent nécessaires.

## Validation locale

- 446 tests JVM Debug, 446 Stable et 446 Release, sans échec, erreur ni test ignoré ;
- 9 tests Python du profil SOL ;
- `assembleStable`, `assembleDebug`, `assembleRelease` et `lintRelease` réussis ;
- aucune différence dans les classes de décision, P01/P02, sizing, stop structurel ou lifecycle public par rapport au HEAD de départ.
