# NMC 2.34.5.5 — Incremental Depth Stability + Storage Fix

Cette version est exclusivement une correction du collecteur V4. Le moteur public
`NMC_SCALP_CV_CORE_V1`, ses trois routes, seuils, niveaux, frais, budgets, alertes et
lifecycle ne changent pas. `realTradingAllowed=false` reste obligatoire.

## Cause live traitée

La session Android 5.4 a prouvé que la queue et le writer étaient sains, mais que le
chemin non ancré demandait un nouveau snapshot après presque chaque diff et produisait
un GAP par message. Cela a créé 458 snapshots de 1000 niveaux et 2 314 GAP en environ
11,5 minutes. La logique de synchronisation décidait aussi trop tôt qu'un snapshot en
avance sur le buffer constituait une rupture.

## Contrat Binance appliqué

- connexion au flux public USD-M `symbol@depth`, dont la cadence par défaut officielle
  est 250 ms ;
- buffer des diffs avant le snapshot REST ;
- abandon des seuls événements où `u < lastUpdateId` ;
- premier événement traité tel que `U <= lastUpdateId <= u` ;
- ensuite `pu == u` de l'événement précédent ;
- quantité nulle conservée comme suppression d'un niveau ;
- snapshot public non signé `GET /fapi/v1/depth?limit=500`, limite officiellement
  supportée et suffisante pour la reconstruction autour du mid et les recherches top-20.

## Stabilité et stockage

Chaque symbole suit les phases `WAITING_BOOTSTRAP`, `BUFFERING`, `SYNCING`,
`RECONSTRUCTIBLE` et `INVALID_WAITING_RESYNC`. Une seule requête REST peut être en vol
par symbole. Les échecs utilisent un backoff borné de 5 à 60 secondes. Un snapshot en
avance sur le buffer attend de futurs diffs au lieu de déclencher une rupture immédiate.

Les diffs bruts non ancrés restent stockés, mais aucun GAP répétitif n'est ajouté.
Une rupture `pu`, un drop interne ou une reconnexion invalide explicitement l'intervalle
une seule fois. Le manifeste exporte heures observées, octets/heure, taux des diffs,
bootstraps et GAP, ainsi qu'une estimation de rétention calculée depuis la borne du store.

## Validation et limite

Les tests synthétiques couvrent dix minutes saines, snapshot en avance, rupture `pu`,
100 diffs en attente, reconnexion, drop, isolation des trois symboles et absence de
croissance GAP/REST proportionnelle. Ils ne remplacent pas une petite session Android
réelle : celle-ci doit confirmer un bootstrap initial par symbole, peu de resynchronisations,
`usableForIncrementalDepthResearch=true` et un volume horaire raisonnable.

Cette version ne contient aucune stratégie de profondeur et ne formule aucune promesse
de performance ou de rentabilité.
