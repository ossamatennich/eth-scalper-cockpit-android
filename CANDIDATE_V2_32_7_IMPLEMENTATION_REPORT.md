# Candidate v2.32.9 — rapport d’implémentation

> Le nom historique du fichier est conservé. L’application produite est **ETH Scalper Cockpit v2.32.9 — Confirmed P01 Dynamic Risk** (`versionCode 23290`, `versionName 2.32.9`).

## Référence Git et portée

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`
- Branche : `agent/v2.32.7-scalp-p01-candidate`
- HEAD au départ : `f4e7693389aa4caa81d36dabd7dd26cd95a2e6b0`
- Branche de base de la PR : `agent/v2.32.6-candidate`
- Aucun changement de branche, aucun merge vers `main` et aucune release définitive.

Principaux fichiers de cette calibration :

- `DynamicTradePlan.java` et `DynamicTradePlanTest.java` (nouveaux) ;
- `CandidateTombstones.java` (nouveau) ;
- `CandidateLifecycle.java`, `MarketWatchService.java` et `SignalSafetyPolicies.java` ;
- `ActivePlanState.java` pour autoriser la persistance des quantités 1–2 ETH ;
- tests d’intégration `ConfirmedP01EntryLifecycleTest.java` et `RangeFadeDiagnosticOnlyTest.java` (nouveaux) ;
- configuration Android, interface, workflows et rapports.

Les seuils C01–C08 et P01 restent inchangés. L’IA reste informative après publication et `realTradingAllowed=false`.

## FRESH EXECUTABLE ENTRY

- Un candidat CONTINUATION est créé ou mis à jour silencieusement ; son premier `createdAt` est conservé.
- `marketableAtCreation` reste une donnée historique et ne peut plus autoriser une confirmation.
- Avant 15 000 ms, `V2329_SILENT_P01_CONFIRMATION_WINDOW` interdit toute publication.
- À partir de 15 000 ms, un snapshot reconstruit au timestamp courant est obligatoire.
- La LIMIT est exécutable uniquement si `ask <= entry` pour LONG ou `bid >= entry` pour SHORT.
- À 120 000 ms exactement, une confirmation reste déterministiquement possible ; après 120 000 ms, le candidat seul expire avec `V2329_PENDING_CANDIDATE_EXPIRED`.
- Si le bid LONG atteint le TP candidat, ou si l’ask SHORT l’atteint, avant un fill confirmé, le candidat devient `MISSED_NO_FILL` avec `V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL`.
- Une tombstone de signature empêche ce candidat manqué d’être recréé au retour du prix.
- Après un fill réellement exécutable : feed frais, cohérence, prix, C04, C07, C08 et P01 sont revalidés avant tout calcul ou effet public.

## DYNAMIC STRUCTURAL STOP

`DynamicTradePlan` est une classe pure et symétrique. Elle définit `A = max(0.35, avgRange20)` et reçoit `E60`, excursion défavorable bid/ask observée pendant les 60 premières secondes.

- `SL_required = max(0.55, 0.70 × A, E60 + 0.20 × A)` ;
- `SL_max = min(2.50, 2.00 × A)` ;
- si le stop requis dépasse le maximum, le plan est refusé par `V2329_STRUCTURAL_STOP_TOO_WIDE` sans clamp artificiel ;
- LONG place le SL sous l’entrée, SHORT au-dessus ; l’arrondi au tick est conservateur et ne rétrécit pas le stop requis.

## DYNAMIC MARKET TARGET

Le coût estimé de recherche est centralisé dans `DynamicTradePlan.ESTIMATED_ROUND_TRIP_COST_PER_ETH = 1.43`.

- `R` est l’espace favorable entre l’entrée et `recentHigh` (LONG) ou `recentLow` (SHORT) ;
- `TP_floor = max(2.80, 1.95 × 1.43)` ;
- `TP_raw = 2.50 × A + 0.25 × R` ;
- le TP est borné entre 2,80 et 5,50 USDT puis arrondi au tick dans le sens conservateur ;
- `TP_distance / SL_required` doit être au moins 1,40, sinon `V2329_REWARD_RISK_INSUFFICIENT` refuse le plan.

## RISK BUDGET SIZING

Le sizing qualité existant calcule maintenant uniquement un plafond supérieur à partir des preuves P01, premium 15 minutes, contexte propre et plafond replay.

- budget par défaut : 10,00 USDT ;
- risque par ETH : `SL_required + 1.43` ;
- quantité risque : `floor(10 / riskPerEth)` ;
- quantité finale : minimum de la quantité risque, du plafond qualité et de 7 ;
- 1 et 2 ETH sont autorisés et ne sont jamais remontés à 3 ;
- si la quantité risque est inférieure à 1, `V2329_RISK_BUDGET_TOO_SMALL` refuse le plan.

Budget, coût, stop, risque par ETH, quantité risque, plafond qualité, quantité finale et perte maximale théorique sont exportés. Le plan, l’écran, la notification, la persistance et le diagnostic utilisent le même objet final.

## RANGE FADE DIAGNOSTIC ONLY

- RANGE_FADE LONG/SHORT reste détecté, dédupliqué, suivi et exporté.
- Son statut live est `DIAGNOSTIC_ONLY` avec `V2329_RANGE_FADE_DIAGNOSTIC_ONLY` et le texte « RANGE_FADE conservé pour calibration — aucune publication finale. »
- Il ne crée ni plan actif, ni quantité publique, ni son, ni vibration et ne bloque pas un futur P01.
- Le moteur RANGE_FADE et ses niveaux théoriques restent disponibles pour les recherches futures.

## TP/SL ONLY ACTIVE LIFECYCLE

- Après publication et persistance atomique, un seul plan final reste `ACTIVE` sans timeout ni invalidation automatique.
- Aucun changement de flow, BTC, momentum, IA, feed stale ou âge ne modifie l’entrée, le TP, le SL ou la quantité.
- Le plan se termine uniquement par `TP_TOUCHED` ou `SL_TOUCHED`.
- La restauration du service reste silencieuse, conserve le même ID de notification et rétablit le verrou mono-plan.
- La réinitialisation des diagnostics conserve le plan actif ; seul TP ou SL efface automatiquement l’état persistant.
- Une seule notification sonore indique la confirmation d’un nouveau P01 final. Les clôtures sont des mises à jour silencieuses.

## Diagnostics v2.32.9

Les événements enregistrent `createdAt`, `confirmationAt`, âge, `marketableAtCreation`, exécutabilité finale, bid/ask finaux, A, E60, R, SL requis/maximal, TP brut/final, reward/risk, coût, budget, quantité risque, plafond qualité, quantité finale, perte théorique et reason code.

Reason codes principaux :

- `V2329_SILENT_P01_CONFIRMATION_WINDOW` ;
- `V2329_PENDING_CANDIDATE_EXPIRED` ;
- `V2329_TARGET_REACHED_BEFORE_CONFIRMED_FILL` ;
- `V2329_RANGE_FADE_DIAGNOSTIC_ONLY` ;
- `V2329_STRUCTURAL_STOP_TOO_WIDE` ;
- `V2329_REWARD_RISK_INSUFFICIENT` ;
- `V2329_RISK_BUDGET_TOO_SMALL` ;
- `V2329_DYNAMIC_PLAN_CONFIRMED`.

## Résultats de calibration fournis

La recherche hors Codex porte sur 14 sessions, 39 695 frames uniques et 15 opportunités P01 propres. Sur le corpus propre disponible :

- 13 trades retenus, 11 TP et 2 SL ;
- net standardisé : +22,2652 USDT par ETH ;
- profit factor : 4,8414 ;
- drawdown maximal : 3,4487 USDT par ETH ;
- 9 sessions positives sur 10 sessions tradées.

Découpage chronologique : découverte 7 trades, 5 TP, 2 SL, +10,1752 USDT/ETH ; holdout historique 4 trades, 4 TP, +8,1275 USDT/ETH ; cas récents corrigés 2 trades, 2 TP, +3,9625 USDT/ETH.

Stress : coût 2,00 et slippage 0,15 donne +12,9052 USDT/ETH ; coût 2,145 et slippage 0,20 donne +10,3702 USDT/ETH.

Sizing avec budget 10 USDT : quantités observées 2–5 ETH, moyenne environ 3,38 ETH, perte maximale modélisée environ 9,91 USDT et résultat théorique environ +75,40 USDT.

Ces chiffres sont des résultats de recherche sur diagnostics, pas une garantie financière ni une promesse de rentabilité future. Aucun replay indépendant supplémentaire n’est revendiqué dans cette branche.

## Limitations

- Les tests JVM couvrent les composants purs et les politiques utilisées par le service ; un essai instrumenté sur appareil reste recommandé avant toute promotion hors brouillon.
- Les anciens formats de playback restent lisibles et n’influencent pas le lifecycle live v2.32.9.
- Aucun ordre automatique, connecteur d’exécution ou secret d’exchange n’a été ajouté.
