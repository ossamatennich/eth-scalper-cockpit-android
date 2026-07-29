# Diagnostics

Le recorder multi-marchés conserve séparément les événements lifecycle et les frames échantillonnées.

- status Android borné ;
- index persistant incrémental ;
- événements répétitifs coalescés ;
- rotation séparée des événements et frames ;
- export ZIP en streaming ;
- reset sans suppression des plans actifs ;
- aucune clé ou donnée sensible dans l’export.

Les événements portent le symbole, le profil, le type, le reason code et les métriques utiles. Une restauration ne compte pas comme un nouveau trade. Les détails bruts restent dans l’écran Diagnostic et dans le ZIP, pas sur le cockpit principal.
