# NMC Stable 6.4 — finition UI mobile

Version : `23464` / `2.34.6.4` / `NMC Stable 6.4`.

Cette livraison est strictement limitée à l'ergonomie et à l'affichage Android. Le moteur `NMC_PROP_DAILY_HYBRID_V4`, son modèle, ses données, son alpha, son sizing et ses règles de trading ne changent pas.

## Finition appliquée

- Le contenu d'Accueil et de Plans n'est plus reconstruit lors d'un simple tick runtime : le scroll reste stable tant qu'un plan visible ne change pas.
- Une seule hiérarchie `ScrollView` verticale est utilisée par écran, sans nested scroll.
- La navigation basse devient une carte compacte hors safe area, avec icône et libellé pour Accueil, Plans et Historique.
- La carte principale, les cartes secondaires, les horaires, le message d'état et les actions ont été compactés.
- ENTRY, TP et SL utilisent une valeur visuelle compacte et tabulaire sans ellipse ; le presse-papiers reçoit toujours la valeur moteur complète non arrondie.
- Les boutons de copie sont plus petits et utilisent une icône vectorielle dédiée.

## Gel et sécurité

- Modèle SHA-256 : `207913d0fc553c6907e93b66b6787b4e3f4f2020dd14dccce654fcc72adbb680`.
- Manifeste gelé SHA-256 : `47b62ac8b29ec7a72b7a3e698e14573528dd4c8dbbdd25aa130170992252e7f3`.
- `realTradingAllowed=false`.
- Aucune API privée et aucun ordre automatique.
