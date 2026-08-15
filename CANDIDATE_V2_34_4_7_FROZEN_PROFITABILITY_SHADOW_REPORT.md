# NMC v2.34.4.7 — Frozen profitability shadow protocol

## Portée

Cette candidate part de NMC 2.34.4.6 et ajoute une expérience entièrement shadow, silencieuse et isolée. Le moteur public ETH/SOL, ses seuils, ses signaux, ses entrées, ses TP/SL, ses quantités, ses alertes, sa persistance et son réarmement ne changent pas. `realTradingAllowed=false` et aucune activation publique ou automatique n’est autorisée.

Le protocole figé est `FROZEN_PROFITABILITY_SHADOW_V1_20260804`, schéma `FROZEN_PROFITABILITY_SCHEMA_V1`, policy `SHADOW_V23447_20260804`, diagnostics `SHADOW_SCHEMA_V8`. Il démarre tous ses compteurs à zéro et accepte uniquement les observations futures hors échantillon. Le corpus `NMC_RELAIS_COMPLET_20260804_DEDUP_113241` (113 241 frames, 84,55 heures) est une référence documentaire, pas une source injectée dans les compteurs.

## Règles figées

- ETH `ETH_RANGE_HIGH_VOLATILITY_V1` : famille contenant RANGE, `A > 2.18175`, entrée au ask courant pour LONG ou au bid courant pour SHORT, TP `3A`, SL `1.5A`.
- SOL `SOL_CONTINUATION_ACCEL38_V1` : famille contenant CONTINUATION, `A > 0.05775` et `m3 / 3 - m8 / 8 > 0.335624`. Les branches canonique (`TP 4A`, `SL 1.5A`) et robuste (`TP 4.5A`, `SL 1.75A`) s’ouvrent simultanément avec la même opportunité et la même entrée.

Les entrées et niveaux sont arrondis de manière conservatrice au tick. La quantité est calculée depuis le budget final de 14,55 USDT, la distance SL arrondie et le coût aller-retour officiel du profil. Les terminaux utilisent le bid pour LONG et l’ask pour SHORT, uniquement sur flux frais. `touchQuote` reste diagnostique ; le résultat est rempli au TP ou SL planifié. Un SL vaut exactement -1 R frais inclus.

## Regroupement, cycle de vie et diagnostics

Une signature structurelle regroupe les observations continues par symbole, côté, famille, origine et extrême du mouvement, y compris au passage des frontières de 15 secondes ou d’une minute. Les registres sont FIFO et bornés à 160 mouvements par symbole. ETH et SOL possèdent chacun au plus un groupe frozen actif. Le cooldown de 180 secondes commence seulement lorsque toutes les branches du groupe sont terminées.

Le lifecycle autorise seulement TP, SL, non résolu ou reset explicite. Le reset interrompt silencieusement les branches frozen sans créer de faux TP/SL et sans toucher aux plans publics. Les événements et le résumé exportent les métadonnées du protocole, le sizing, les overlaps, les doublons, le temps frais plafonné et les buckets de sensibilité qui n’ouvrent jamais de plan.

## Référence historique, sans garantie

Les résultats historiques corrigés fournis pour cadrer la recherche sont : ETH Range HV 10 TP / 4 SL sur 14 résolus, +8,482 R et 0,166/h ; SOL canonique 6 TP / 4 SL sur 10 résolus, +4,071 R et 0,118/h ; portefeuille 16 TP / 8 SL sur 24 résolus, +12,553 R, PF R 2,569, espérance +0,523 R, drawdown maximal 2 R et 0,284/h. Ces chiffres ne constituent ni une promesse de rentabilité ni une validation future.

De nouvelles sessions Samsung et de nouveaux diagnostics hors échantillon sont indispensables avant toute décision. La promotion publique est explicitement interdite dans cette version.
