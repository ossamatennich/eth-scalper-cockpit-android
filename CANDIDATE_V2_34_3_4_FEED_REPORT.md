# NMC v2.34.3.4 — réparation définitive du démarrage des flux

## Symptôme confirmé

Après installation de v2.34.3.3, ETH, SOL et BTC pouvaient rester vides. Le bouton
« Réinitialiser diagnostic » faisait ensuite démarrer les flux sans changer de réseau.

## Cause isolée

Le réseau public Binance Futures est disponible : une connexion directe à
`wss://fstream.binance.com` a reçu un `bookTicker` ETH Futures valide. Le second démarrage
réussi après reset montre que le blocage provenait du recorder persistant conservé lors des
mises à jour Android. La v2.34.3.3 avait supprimé le scan des JSONL, mais ouvrait encore
l'ancien index et publiait un diagnostic persistant avant de lancer la connexion.

## Correction v2.34.3.4

- lancement immédiat du WebSocket Futures principal et du préchargement REST ;
- aucune ouverture de l'ancien index recorder avant les requêtes réseau ;
- écritures événements, frames et index sur le thread dédié `nmc-diagnostic-io` ;
- flush/reset synchronisés avec ce thread, sans toucher aux plans actifs ;
- reconnexion planifiée avant toute publication diagnostic en cas d'échec WebSocket.

Ainsi, un journal ancien, volumineux, lent ou corrompu peut au pire retarder son propre
enregistrement. Il ne peut plus retarder le WebSocket, le fallback REST, l'évaluation du moteur
ou la mise à jour des prix.

## Invariants

Aucune formule, aucun seuil, aucune règle P01/P02, aucun sizing et aucun niveau de plan n'a été
modifié. `realTradingAllowed=false` et un plan publié se termine uniquement par TP ou SL.

