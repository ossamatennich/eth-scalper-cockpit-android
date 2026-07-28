# First Natural Multi-Market Diagnostic — Findings

## Corpus observé

- Durée naturelle : **2 h 16**.
- Événements : **9 475**.
- Catégories dominantes : **2 798 `ADMISSION_REJECTED`**, **2 586 `ENGINE_DIAGNOSTIC`**, **2 544 `RAW_DECISION`**.
- Ces trois catégories représentaient environ **83,7 %** du journal et motivaient le coalescing technique de v2.34.1.0.
- Les frames génériques de l’ancienne candidate ne contenaient que SOL ; v2.34.1.0 ajoute la copie d’observabilité ETH sans modifier son moteur historique.
- Des périodes intermittentes `SOL_FEED_STALE`, `BTC_REFERENCE_FEED_STALE` et `REST_FALLBACK` ont été observées, avec parfois des compteurs WebSocket à zéro. Les seuils de fraîcheur ne sont pas modifiés ; seules les transitions sont désormais documentées précisément.

## Signaux et diagnostics constatés

- Un plan ETH SHORT P01, score 96, a approché environ 86 % de son TP avant de terminer au SL.
- Le diagnostic `CONTINUATION_SANS_ALIGNEMENT_8M_OU_FLOW` a été observé.
- Plusieurs candidats SOL ont été rejetés par `PRIX_DEJA_TROP_LOIN`.
- Un plan ETH a été observé.
- Aucun plan SOL final n’a été publié pendant cette session.

## Décision de cette candidate

Aucune conclusion de calibration n’est tirée d’une seule session. La candidate ne renforce aucun veto ETH, ne modifie ni `PRIX_DEJA_TROP_LOIN`, ni les seuils SOL, ni le sizing, ni les TP/SL. Les changements sont exclusivement visuels, ergonomiques et liés à la performance/observabilité du recorder.

Ces données de recherche ne constituent ni une promesse de performance ni une garantie financière.
