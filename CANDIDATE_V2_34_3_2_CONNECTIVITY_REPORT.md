# NMC v2.34.3.2 — Résilience des flux publics

## Défaut corrigé

La version 2.34.3.1 utilisait une seule route WebSocket Futures et une seule route REST.
Une indisponibilité, un filtrage réseau ou une réponse HTTP non exploitable pouvait laisser les
trois marchés sans prix, tandis que l'erreur restait invisible dans l'interface.

## Correction

- rotation automatique entre trois routes WebSocket publiques ;
- secours REST public indépendant pour les prix, bougies et trades de chaque marché ;
- contrôle des codes HTTP et des données reçues avant leur admission ;
- reprise immédiate lors du retour du réseau ;
- nouvelle tentative de préchargement après un échec complet ;
- service Android `START_STICKY`, redémarrage planifié et wake lock détenu pendant sa vie ;
- état `connecté` fondé sur la fraîcheur réelle des prix ETH, SOL et BTC, même en secours REST ;
- source active et dernière erreur visibles dans l'écran Diagnostic.

Le secours Spot public rétablit la visibilité des cours si toutes les routes Futures sont
inaccessibles, mais il est explicitement non autoritaire : il ne peut pas publier un nouveau
plan. Les publications reprennent uniquement avec des données Futures publiques fraîches.

Les routes de secours restent des API publiques de données de marché. Aucune clé privée, aucun
compte et aucun ordre automatique ne sont utilisés. Les règles de signal, TP, SL, sizing, P01,
P02, OLS60, persistance et lifecycle TP/SL n'ont pas été modifiées.

Android et le réseau mobile ne permettent pas de promettre mathématiquement zéro seconde de
coupure. Le moteur reste toutefois actif en arrière-plan, surveille la connectivité, bascule vers
une source disponible et reprend automatiquement sans intervention de l'utilisateur.
