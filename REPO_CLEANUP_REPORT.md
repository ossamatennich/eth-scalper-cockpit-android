# Rapport de nettoyage différé du dépôt

Ce rapport est volontairement séparé de la candidate moteur. Aucun fichier historique n’a été supprimé et l’historique Git n’a pas été réécrit.

## Éléments historiques repérés

- `ETH_V233_FREE_WORK_2026-07-17.zip`
- `.github/workflows/v233-free-research.yml`
- `.github/workflows/build-v2326-candidate.yml`
- les changelogs racine `CHANGELOG_v2_19_0_ANDROID.txt` à `CHANGELOG_v2_28_1_ANDROID.txt`
- `README_v2_19_0_ANDROID.md`
- les audits/changelogs/replays v2.18 sous `app/src/main/assets/www/`
- les fichiers `.gradle/9.2.0/` et `.gradle/buildOutputCleanup/` déjà suivis par Git sur la branche source.

## Recommandations ultérieures

- Archiver le ZIP de recherche v2.33 et son workflow dans une branche ou un dépôt de recherche dédié.
- Désactiver ou supprimer le workflow v2.33 seulement après confirmation qu’aucune reproduction historique n’en dépend.
- Regrouper les changelogs très anciens dans une archive documentaire.
- Retirer du suivi Git les caches `.gradle/` lors d’une PR de maintenance dédiée.
- Évaluer si les actifs web v2.18/v2.19 sont encore utilisés par un flux supporté avant suppression.

## Pourquoi ce nettoyage n’est pas mélangé à v2.32.7

La candidate modifie un moteur temps réel, son lifecycle et ses notifications. Supprimer ou déplacer simultanément des recherches historiques rendrait la revue plus risquée et compliquerait un rollback. La seule mesure préventive ajoutée est `.gitignore` pour éviter de nouveaux produits Gradle/build non suivis ; les fichiers déjà suivis restent intacts.

Le workflow v2.33 ne cible pas la nouvelle branche candidate et ne perturbe donc pas son push automatique.
