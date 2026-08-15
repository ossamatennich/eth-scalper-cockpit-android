# Audit de retrait — NMC 2.34.4.9

## Éléments retirés explicitement

- les douze classes runtime `ScalpAction*` et leur mode persistant ;
- `LegacyPublicComparator` et ses événements/résumés ;
- les cinq voies publiques 4.8 ;
- la carte Outils et les actions `ACTION_ON` / `DIAGNOSTICS_ONLY` ;
- les tests fonctionnels 4.8 remplacés par la suite CV Core 4.9 ;
- le rapport candidat 4.8 obsolète.

Ces éléments n’étaient plus nécessaires après le remplacement du pipeline public. Leur retrait évite deux moteurs concurrents et tout fallback vers une voie 4.8.

## Éléments conservés

- `SignalEngine`, `CandidateLifecycle`, P01/P02 : sources des observations et diagnostics ;
- les flux Futures ETH/SOL/BTC, leur fraîcheur, la reconnexion et le recorder ;
- `ActivePlanPersistence`, les alertes et le lifecycle TP/SL ;
- les recherches shadow/frozen : diagnostics historiques isolés, sans influence publique ;
- `LegacyV23448ActivePlanCompatibility` : seule exception, limitée à identifier/restaurer un plan format 3 déjà actif et à purger les préférences inactives. Elle ne construit aucun plan.

## Contrôles

Le runtime et l’UI ne contiennent plus `NMC_SCALP_ACTION_V1`, les cinq routes 4.8, les deux modes, `LegacyPublicComparator` ni une classe `ScalpAction*`. Les seules mentions historiques restent dans ce rapport, le changelog, le rapport 4.9 et la compatibilité de migration autorisée.

La suppression ne modifie ni le feed Binance Futures, ni la signature Stable, ni l’applicationId, ni les diagnostics, ni `realTradingAllowed=false`.
