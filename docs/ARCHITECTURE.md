# Architecture NMC

NMC analyse tous les marchés enregistrés dans `MarketRegistry`.

- `MarketProfile` contient les paramètres propres à un marché : symbole, tick, coûts, limites et budgets.
- `MarketRuntime` conserve l’état indépendant du marché : flux, bougies, candidats, plan actif, réarmement et diagnostics.
- `MarketDataRouter` distribue les données au runtime correspondant.
- `MarketPlanOrchestrator` gère le lifecycle d’un plan sans mélanger les symboles.
- `SharedReferenceContext` contient le contexte BTC commun, non tradable.

ETH et SOL peuvent donc être actifs simultanément. Un plan ne bloque que les nouveaux plans du même symbole. Un futur marché se rajoute par un profil et un runtime, sans recopier le service principal.

Après publication, entrée, TP, SL et quantité sont immuables. Seuls `TP_TOUCHED` et `SL_TOUCHED` terminent un plan. Aucun ordre automatique n’est envoyé.
