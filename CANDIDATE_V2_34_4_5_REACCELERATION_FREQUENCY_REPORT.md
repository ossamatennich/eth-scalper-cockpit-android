# NMC v2.34.4.5 — Reacceleration and guarded frequency shadow research

Cette candidate s’appuie sur les conclusions de recherche communiquées pour 11 diagnostics couvrant environ 81,74 heures. Ces diagnostics ne sont pas présents dans cette tâche et n’ont donc pas été rejoués ici.

La version reste exclusivement observationnelle. Le moteur public ETH/SOL, ses entrées, TP, SL, quantités, alertes, persistance, réarmement et terminaux ne sont pas modifiés. `realTradingAllowed=false` demeure obligatoire.

Le baseline universel SOL P01 est remplacé par `SOL_P01_QUALITY_GUARD_V2`, propre à SOL et uniquement shadow. `ETH_FLOW_REACCELERATION_V2` exige des métriques de continuation, une branche A ou B et dix secondes continues de stabilité avant de construire un plan shadow ré-ancré et dimensionné frais inclus. L’ancienne continuation ETH reste un comparateur sans ouverture.

`ETH_RANGE_FADE_QUARANTINE` interdit désormais toute ouverture shadow Range Fade. `ETH_RANGE_RECLAIM_RESEARCH` suit seulement les reprises causales. `ETH_NO_RETRACE_BREAKOUT_RESEARCH` regroupe les objectifs atteints avant confirmation/fill sans créer de stratégie immédiate ni de plan fictif. Toutes les mémoires sont bornées et toutes les opérations shadow sont fail-open.

Les chiffres historiques fournis motivent la recherche mais ne constituent ni une garantie, ni une validation hors échantillon, ni une promesse de rentabilité. De nouvelles sessions réelles sur Samsung et leurs diagnostics exportés restent nécessaires avant toute décision d’activation publique.
