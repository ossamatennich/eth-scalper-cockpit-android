# NMC v2.34.4.6 — Shadow telemetry integrity

Cette correction reste exclusivement `SHADOW A/B OBSERVABILITY ONLY`. Aucun signal, filtre, niveau, sizing, terminal, réarmement, stockage ou avertissement public n’est modifié.

Quatre défauts audités sont corrigés. Range Reclaim mesure désormais la reprise depuis `movementExtreme`, qui représente l’extrême réel du mouvement, tandis que `movementOrigin` reste une donnée diagnostique distincte. Les événements No-Retrace sont attribués au composant officiel `ETH_NO_RETRACE_BREAKOUT_RESEARCH`. L’horloge de stabilité Reacceleration est entièrement remise à zéro lorsqu’un plan shadow est actif. Enfin, les mouvements Range Reclaim et No-Retrace sont exposés dans des snapshots FIFO bornés à 160 éléments, avec leurs doublons regroupés sans spam d’événements.

Les chemins shadow et leurs snapshots restent fail-open : une `RuntimeException` ne peut pas interrompre le moteur public. Les collections sont bornées et les valeurs non finies ne sont pas sérialisées.

Une validation sur de nouvelles sessions Samsung reste nécessaire. Cette instrumentation ne constitue aucune promesse de rentabilité et n’autorise aucun trading réel : `realTradingAllowed=false`.
