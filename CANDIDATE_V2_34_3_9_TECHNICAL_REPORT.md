# NMC v2.34.3.9 — statut, export et observabilité

## Portée

Cette correction est strictement technique. Elle ne modifie ni P01, ni P02, ni OLS60, ni les admissions, entrées, LIMIT, TP, SL, quantités, sizing, seuils, calibrations ou lifecycle. `realTradingAllowed=false` et la fin TP/SL uniquement restent inchangés.

## Cause confirmée

`StatusPayloadPolicy.sizeBytes(JSONObject)` appelait directement `state.toString().getBytes(UTF_8)`. Sur Android, `JSONObject.toString()` peut renvoyer `null` si une valeur imbriquée ne peut pas être sérialisée. Le chemin concret venait des détails produits par `MarketPlanOrchestrator.record(...)` : en l’absence de signal, le champ enrichi `entry` était réinjecté avec `Double.NaN` après la normalisation initiale du recorder. Il remontait ensuite dans `diagnostics[].entry`.

La correction supprime cette réinjection non finie et ajoute une normalisation récursive défensive des `JSONObject`, `JSONArray`, maps, collections, tableaux, nombres non finis et valeurs Java inconnues. Le chemin précis de toute valeur remplacée est journalisé.

## Statut et export

- chaque chaîne est reparsée avec `new JSONObject(serialized)` avant persistance ou broadcast ;
- un statut invalide ne remplace jamais le dernier statut valide ;
- le statut minimal conserve les flux, marchés, BTC, plans, canal sonore et sécurité ;
- l’export utilise un identifiant unique, attend le flush et son acquittement, puis capture un seul snapshot ;
- un timeout utilise explicitement le dernier statut valide et est journalisé ;
- le manifeste contient `snapshotAt`, `requestId`, `flushCompleted`, `statusMode` et `statusSha256`.

## Allègement diagnostic

L’identité des événements analytiques coalescibles n’utilise plus le texte métrique variable. Elle repose sur symbole, type, reason code, classification, sleeve, side et family. Les événements lifecycle, feed, erreur, alerte et persistance ne sont jamais coalescés. Les synthèses conservent premier et dernier payload, dernier texte, compteur et bornes numériques.

Mesure JVM déterministe : 3 000 diagnostics `V230_NO_EDGE` dont le texte et les métriques changent passent de 705 875 octets à 1 139 octets de JSON synthétique, soit **99,8386 %** de réduction. Le test historique de durée longue conserve 10 000 événements sous 25 écritures et un statut de huit heures à 17 799 octets. Cette mesure valide l’algorithme ; la réduction physique sur un prochain téléphone dépendra de la diversité réelle des identités stables.

## Alerte sonore

Le chemin sonore central et le canal `nmc_final_signal_loud_v2` ne changent pas. Les diagnostics ajoutent permission, activation globale, importance, vibration, URI, résolution et ouverture de la ressource, taille/durée, volume Alarme, ringer, DND, batterie, restriction d’arrière-plan, tentative, ID, signature, timestamp, résultat et motif d’échec. Aucun réglage Android n’est modifié automatiquement.

Correction implémentée et validée en source/CI ; validation physique Samsung encore requise.

## Validation locale

- 413 tests JVM distincts par variante, zéro échec, erreur ou test ignoré ;
- Debug, Stable et Release : 1 239 exécutions cumulées ;
- validateur SOL Python : 9/9 ;
- `assembleDebug`, `assembleStable`, `assembleRelease` : PASS ;
- `lintRelease` : PASS, zéro erreur.
