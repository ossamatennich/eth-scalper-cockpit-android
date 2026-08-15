# NMC Stable 6.5 — finition opérationnelle et alerte forte

Version : `23465` / `2.34.6.5` / `NMC Stable 6.5`.

Cette livraison est strictement limitée à l'UI, aux alertes Android et à la présentation du statut des plans. Le moteur `NMC_PROP_DAILY_HYBRID_V4`, l'alpha, le modèle ExtraTrees, les seuils, les barrières, le sizing, le risque et l'univers de 53 actifs restent inchangés.

## Alertes V4

- Les événements V4 utilisent désormais le canal sonore NMC éprouvé `nmc_final_signal_loud_v2`.
- Ce canal conserve le son `eth_alert_loud`, l'importance HIGH et la vibration longue `{0, 750, 180, 750, 180, 1200}`.
- Les alertes fortes couvrent le premier état actionnable, l'exécution d'une entrée déclarée, le TP et le SL.
- La déduplication persistante est indexée par `plan_id + événement` et résiste au redémarrage.
- Android 13+ demande explicitement `POST_NOTIFICATIONS` une seule fois, sans contourner un refus utilisateur.

## Présentation opérationnelle

- La quantité dispose du même bouton de copie que ENTRY, TP et SL ; le presse-papiers reçoit la valeur exacte du moteur.
- LONG reste vert, SHORT rouge et le symbole est blanc, sur une ligne séparée et auto-ajustée.
- Les cartes et l'historique affichent les statuts persistants `ORDRE POSÉ`, `EN COURS`, `TP ATTEINT` et `SL ATTEINT`.
- Les raisons de statut indiquent clairement l'attente du prix d'entrée ou l'entrée exécutée.
- Le scroll stable, la navigation basse, les safe areas et le multi-plan de la 6.4 sont conservés.

## Gel et sécurité

- Modèle SHA-256 : `207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680`.
- Manifeste gelé SHA-256 canonique : `47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3`.
- `realTradingAllowed=false`.
- Aucune API privée, aucune création/annulation d'ordre et aucun trading automatique.
