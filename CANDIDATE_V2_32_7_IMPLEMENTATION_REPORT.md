# Candidate v2.33.1 — rapport d’implémentation

> Le nom historique du fichier est conservé. L’application produite est **ETH Scalper Cockpit v2.33.1 — Dual Sleeve Diagnostic Fix** (`versionCode 23310`, `versionName 2.33.1`).

## Référence et périmètre

- Dépôt : `ossamatennich/eth-scalper-cockpit-android`.
- Branche modifiée : `agent/v2.32.7-scalp-p01-candidate`.
- HEAD de départ vérifié localement, sur `origin` et dans la PR : `28b3bc6b89dcf3f353a3436836d8b17c24dfecd6`.
- PR nº 2 : ouverte, en brouillon, non fusionnée, base `agent/v2.32.6-candidate`.
- `main` n’a pas été modifiée.
- Mode : `RESEARCH_ONLY`, `realTradingAllowed=false`, aucun ordre automatique, aucune clé exchange et IA strictement informative après publication.

## Correctif technique v2.33.1

- `MarketWatchService.evaluateSignal()` exécute d’abord le lifecycle observé, reconstruit le snapshot, puis recalcule le feed et le setup.
- `SetupTracker.observe()` n’est appelé que si le feed est frais, aucun plan final n’est actif et le réarmement terminal est terminé. Le court-circuit Java empêche tout appel lorsque la création est interdite.
- `SetupTracker.reset()` remet explicitement l’état à `NONE` au démarrage, lors du reset diagnostic et immédiatement après `TP_TOUCHED` ou `SL_TOUCHED`.
- Un setup apparu pendant un feed périmé, un plan actif ou le réarmement n’est donc pas consommé. Il peut devenir la première apparition dès que la création redevient autorisée, notamment exactement à 180 000 ms.
- `trendRegime60()` utilise désormais en priorité les closes de `ethCandles`, alimentés par le préchargement REST 180 bougies, le WebSocket 1m et le fallback REST.
- Seules les bougies `openTime <= confirmationAt` et les closes finis strictement positifs sont retenus. Le `snapshot.ethLast` valide remplace la minute courante ; 60 minutes consécutives restent obligatoires.
- `marketFrames` reste inchangé pour les diagnostics et le playback, mais ne conditionne plus le fonctionnement P02 après démarrage ou reset.

## Architecture v2.33.0 conservée

- `NormalizedSignalMetrics.java` : calcul directionnel pur et symétrique de `A`, `E`, `e`, `R`, `room`, `m1`, `m3`, `m8`, `f30`, `f60`, `volumeRatio` et `directionalEdge`.
- `P01SleeveFilter.java` : filtre P01 précoce/différé exact, avec reason code par veto.
- `P02SleeveFilter.java` : calcul causal C1/C2, détection d’apparition exacte, préfiltre et confirmation P02.
- `TrendRegime60.java` : OLS causal sur exactement 60 closes minute et classification `TREND`/`REVERSAL`.
- `TerminalRearmPersistence.java` et `SharedPreferencesTerminalRearmBackend.java` : réarmement terminal de trois minutes, persistant et atomique.
- `DynamicTradePlan.java` : plan structurel et sizing financier purs, inchangés dans v2.33.1.
- `CandidateLifecycle.java` et `MarketWatchService.java` : intégration sans effet public avant confirmation complète.
- `.github/workflows/build-v2327-candidate.yml` : validation CI complète et artefact debug nommé v2.33.1.

Une tolérance numérique minimale de `1e-12` est appliquée uniquement aux comparaisons de frontières calculées en virgule flottante. Elle évite qu’une valeur mathématiquement égale à `1,60`, par exemple, soit rejetée parce que le double calculé vaut `1,59999999999999`. Aucun seuil métier n’est déplacé ; les tests à ±`1e-9` distinguent toujours les deux côtés de chaque frontière.

## Sleeve P01

Les candidats CONTINUATION existants restent la source P01. La publication exige toujours, dans l’ordre : feed ETH frais, données cohérentes, LIMIT actuellement exécutable, revalidation du prix, C04, C07, C08 et P01 inchangés, puis le filtre défini en v2.33.0.

- Phase précoce : âge ≤ 25 000 ms, `room >= 1.60`, `m1 <= 1.80`, `f30 <= 0.60`, confluence `flowBacked OR priceLed`, puis rejet consommé `m8 > 2.50 AND f30 < 0.15`.
- Phase différée : `25 000 < âge <= 90 000 ms`, `room >= 1.30`, `e <= 0.80`, `f30 <= 0.60`, `f60 <= 1.00` et support flow/excursion défini.
- `volumeRatio <= 3.00` est global.
- Au-delà de 90 000 ms, seul le candidat silencieux expire. Aucun plan publié n’est affecté.

Le cooldown fixe de 18 minutes depuis la confirmation précédente a été supprimé du moteur.

## Sleeve P02 C1/C2

`setupCandidateFor()` utilise exactement les formules C1/C2 fournies. Un P02 n’apparaît que lorsque le setup courant est non `NONE` et différent du setup de l’évaluation précédente ; 27 frames du même run ne créent donc pas 27 candidats.

À l’apparition exacte, le préfiltre normalisé est appliqué sans modification. Le candidat est entièrement silencieux. Pour le choix technique non spécifié de son entrée provisoire, l’implémentation la plus directe et cohérente avec le moteur existant a été retenue : ask courant pour un LONG et bid courant pour un SHORT. Les distances provisoires `2,80`/`1,35` ne servent qu’au pending, à la déduplication et à `TARGET_REACHED_BEFORE_CONFIRMED_FILL`.

La confirmation exige `20 000 < âge <= 45 000 ms`, la LIMIT réellement exécutable, C04/C07/C08/P01 inchangés, le filtre P02 exact, puis OLS60. Après 45 000 ms, seul le candidat P02 expire silencieusement.

## Régime OLS60 causal

Le service fournit à `TrendRegime60` les closes minute préchargés de `ethCandles` dont `openTime <= confirmationAt`. La classe conserve la dernière valeur finie et strictement positive de chaque minute, puis ajoute ou remplace la minute courante par `snapshot.ethLast` seulement si cette valeur est valide. Une minute absente parmi les 60 minutes consécutives attendues entraîne `V2330_P02_OLS60_INSUFFICIENT`.

- `TREND` : `2.00 <= T60 <= 8.00`.
- `REVERSAL` : `-12.00 <= T60 <= -2.00`, `m8 >= 1.00`, `f60 >= 0.50`, `e <= 0.10`.
- Les familles finales sont distinctes : `P02_TREND` et `P02_REVERSAL`.

## Quotes et progression avant fill

- LONG : seule une quote bid finie et strictement positive peut modifier l’excursion défavorable ou favorable avant fill.
- SHORT : seule une quote ask finie et strictement positive peut les modifier.
- Zéro, `NaN` et infini sont ignorés ; ils ne sont jamais remplacés par zéro.
- La progression vers la cible avant fill utilise cette excursion favorable validée, et non un `ethLast` de substitution.

## Plan dynamique final

Constantes séparées :

- résultat estimé : `RESULT_ROUND_TRIP_COST_PER_ETH = 1.43` ;
- réserve d’exécution pour le sizing : `RISK_EXECUTION_ALLOWANCE_PER_ETH = 2.35` ;
- budget de risque : `DEFAULT_RISK_BUDGET_USDT = 10.00`.

Formules appliquées sans calibration supplémentaire :

- `A = max(0.35, avgRange20)` ;
- `SL_required = max(0.55, 1.00*A, E + 0.20*A)` ;
- `SL_max = min(2.50, 2.00*A)` ;
- rejet sans clamp si `SL_required > SL_max` ;
- `TP_floor = max(2.80, 1.95*1.43)` ;
- `TP_raw = 2.70*A + 0.20*R` ;
- `TP_distance = clamp(TP_raw, TP_floor, 5.50)` ;
- rejet si `grossRR < 1.40` ;
- arrondi conservateur floor pour LONG, ceil pour SHORT, sans réduction du stop requis.

## Sizing par risque réel

Après arrondi du stop :

- `riskPerEth = roundedStopDistance + 2.35` ;
- `riskQuantity = floor(10.00 / riskPerEth)` ;
- `finalQuantity = min(riskQuantity, qualityCap, 7)` ;
- rejet si la quantité est inférieure à 1 ;
- `theoreticalMaximumLoss = finalQuantity * riskPerEth <= 10.00` avec tolérance numérique minimale.

Le plafond qualité existant reste uniquement un maximum. Les quantités 1 et 2 ETH ne sont jamais remontées à 3 ETH. La quantité finale est copiée sans mutation dans le plan, l’écran, la notification, la persistance et les diagnostics.

## Réarmement terminal persistant

Le verrou d’un plan actif reste absolu. Seuls `TP_TOUCHED` et `SL_TOUCHED` terminent un plan publié. Au terminal réel, `lastTerminalAt` est écrit par `SharedPreferences.commit()` puis :

- tout nouveau candidat final est bloqué avant 180 000 ms ;
- il est autorisé exactement à partir de 180 000 ms ;
- le timestamp est restauré après redémarrage ;
- la restauration d’un plan actif garde la priorité ;
- aucune alerte sonore n’est produite pendant le réarmement.

## RANGE_FADE et lifecycle actif

RANGE_FADE reste détecté, dédupliqué, suivi théoriquement et exporté sous `DIAGNOSTIC_ONLY`. Il ne crée ni plan final, ni quantité publique, ni son, ni vibration et ne bloque pas un futur P01/P02.

Après publication, le plan final est immuable : une seule position active, aucun timeout, aucune invalidation analytique, aucune modification de l’entrée/TP/SL/quantité et fin exclusivement au TP ou au SL. La persistance/restauration silencieuse et l’identifiant de notification stable restent en place.

## Diagnostics

Les exports historiques sont conservés. S’y ajoutent : sleeve, mode P02, âge, métriques normalisées, OLS count/slope/T60/dernier timestamp, détail SL/TP, `resultCost`, `riskAllowance`, `riskPerEth`, quantités risque/qualité/finale, perte maximale théorique, `terminalAt`, `rearmRemainingMs` et reason code précis.

## Corpus de recherche et limites

Le corpus d’analyse fourni couvre **14 sessions, 39 695 frames uniques et environ 77,14 heures**. Cette branche applique uniquement le correctif de raccordement demandé ; aucune formule, aucun seuil, aucun coût, aucun sizing et aucune calibration n’ont été modifiés. Aucun recalibrage ni replay de recherche supplémentaire n’a été réalisé ici. Les résultats historiques ne constituent ni une promesse ni une garantie de performance financière future.
