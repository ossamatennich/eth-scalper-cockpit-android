# NMC v2.34.3.7 — correction de la cause racine du flux vide

## Constat reproduit par le code

Le bouton « Réinitialiser diagnostic » vidait simultanément les événements, les frames et les
résumés en mémoire. Le statut recommençait alors à être publié. Ce comportement pouvait donner
l’impression que le bouton reconnectait Binance, alors qu’il supprimait surtout le travail lourd
et concurrent effectué lors de chaque fabrication du statut.

Le chemin temps réel effectuait notamment :

- la copie de tous les événements de chaque marché pour n’en conserver que vingt ;
- le parcours de tous les événements pour recalculer les compteurs ;
- la sérialisation périodique de jusqu’à 7 200 frames historiques ;
- plusieurs parcours de collections modifiées en parallèle par les callbacks WebSocket et REST ;
- l’abandon silencieux du statut complet à la moindre exception.

## Correction

- `MarketDiagnosticRecorder` maintient ses compteurs à chaque ajout/retrait et expose seulement
  une queue bornée d’événements récents ;
- `StatusPayloadPolicy` ne copie plus la totalité des diagnostics ;
- les frames historiques ne sont plus sérialisées dans le chemin du flux ; leur source canonique
  reste le journal persistant exporté en streaming ;
- les mutations REST sont sérialisées avec l’ingestion WebSocket, l’évaluation et le snapshot ;
- les résumés de recherche sont mis en cache hors du statut fréquent ;
- une erreur de statut détaillé est journalisée et déclenche un statut minimal contenant les
  prix et la santé du flux, au lieu de laisser l’interface vide.

## Source de marché

La route principale reste exactement `wss://fstream.binance.com` et les endpoints REST principaux
restent sous `https://fapi.binance.com/fapi/v1`. Les fallbacks restent secondaires.

## Périmètre préservé

Aucune formule ou règle de marché n’a été modifiée. Les plans publiés restent immuables et se
terminent uniquement au TP ou au SL. `realTradingAllowed=false` demeure inchangé.

## Limite honnête

Les tests déterministes et de concurrence prouvent l’élimination du couplage reset/statut dans le
code. La validation finale sur le téléphone réel doit être faite avec l’icône clairement nommée
« NMC Stable 3.7 » ; la capture reçue affichait encore la version 2.34.3.2.
