# Rapport d’optimisation différée du recorder

## Décision pour v2.32.7

Aucune refonte structurelle du recorder n’a été appliquée dans cette candidate.

Le format JSONL persistant, les exports JSON/CSV et les champs existants sont conservés pour préserver la compatibilité avec les diagnostics déjà utilisés. La candidate ajoute uniquement les données nécessaires à P01, au contexte 15 minutes, aux règles C04/C07/C08, au fill et à la séparation réalisé/latent.

## Données conservées ou ajoutées

- bougies ETH/BTC et bid/ask
- move1, move3, move8 et move15
- fenêtres de flow
- décisions, reason codes, candidats, signaux et lifecycle
- P01, premium 15 minutes et valeurs alignées C04/C07/C08
- informations marketable/touch/fill simulé/confirmation manuelle
- résultats terminaux et risques ouverts.

## Points d’optimisation identifiés

- `jsonlFileToJsonArrayString` reconstruit un tableau complet à la demande.
- Certains exports maintiennent des représentations JSON et JSONL parallèles.
- La génération de ZIP/export peut encore charger des contenus volumineux en mémoire.
- Les frames persistantes restent échantillonnées toutes les cinq secondes.

## Proposition pour une branche dédiée

1. Export ZIP en streaming fichier par fichier.
2. Pagination/lecture en flux du JSONL au lieu d’un tableau JSON complet.
3. Conservation du JSONL comme source canonique et génération des vues JSON seulement à la demande.
4. Tests de compatibilité sur plusieurs ZIP réels avant migration.
5. Mesures mémoire/temps sur une session longue avant et après.

Ces changements sont différés car les archives historiques nécessaires au test de non-régression n’étaient pas disponibles localement et qu’une optimisation non vérifiée aurait augmenté le risque de la candidate.
