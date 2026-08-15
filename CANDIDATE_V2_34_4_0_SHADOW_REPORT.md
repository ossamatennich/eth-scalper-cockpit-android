# NMC v2.34.4.0 — rapport shadow A/B

## Portée

Cette version introduit uniquement de l’observabilité de recherche sous la politique
`SHADOW_V23440_20260801`. Le moteur public, ses filtres P01/P02, OLS60, ses timings, ses
entrées, TP, SL, quantités, alertes et son lifecycle ne sont pas modifiés.

## Politiques observées

- `P01_FINAL_CONFIRMATION_GUARD` classe chaque confirmation publique P01 en `KEEP` ou `BLOCK`.
- `P02_ANTI_EXHAUSTION` mesure la marge, le flow et l’extension des P02 publics.
- `P01_PULLBACK_RESUMPTION` simule une reprise après retracement sur un candidat P01 réel.
- `ETH_MID_VOL_TREND_EXPANSION` simule une continuation ETH de volatilité intermédiaire.

Les plans ajoutés en shadow sont causaux, silencieux, dédupliqués, bornés à un par symbole
et se terminent uniquement par `SHADOW_TP_TOUCHED` ou `SHADOW_SL_TOUCHED`. Ils n’écrivent
jamais dans le plan actif, la persistance, le réarmement ou les notifications publiques.

## Économie nette diagnostique

`SHADOW_FEE_AWARE_SIZING` compare la quantité active à une quantité diagnostique calculée
avec `stopDistance + estimatedRoundTripCostPerUnit`. Cette sonde ne change jamais la quantité
publique. Les valeurs numériques restent des nombres JSON et les indicateurs restent des
booléens JSON.

## Événements exportés

`SHADOW_AB_DECISION`, `SHADOW_PLAN_OPENED`, `SHADOW_PLAN_SKIPPED`,
`SHADOW_TP_TOUCHED`, `SHADOW_SL_TOUCHED`, `SHADOW_FEE_AWARE_SIZING` et
`SHADOW_STATE_RESET` sont enregistrés dans le recorder multi-marchés et donc inclus dans
les exports diagnostics existants.

## Limites

Ces résultats ne constituent ni une calibration publique, ni une promesse de performance.
Une validation hors échantillon sur de nouveaux diagnostics Samsung est encore requise.
`realTradingAllowed=false` demeure inchangé et aucun trading réel n’est autorisé.
