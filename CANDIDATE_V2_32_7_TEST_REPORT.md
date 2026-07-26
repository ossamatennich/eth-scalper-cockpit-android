# Candidate v2.32.9 — rapport de tests

> Le nom historique du fichier est conservé. Tous les résultats concernent **ETH Scalper Cockpit v2.32.9 — Confirmed P01 Dynamic Risk**.

## Environnement

- Windows / PowerShell
- Android SDK : `C:\Users\Tenni\AppData\Local\Android\Sdk`
- JDK : Android Studio JBR 17
- Gradle : 8.10.2 temporaire, le dépôt ne fournissant pas de wrapper fonctionnel
- `versionCode=23290`, `versionName=2.32.9`, `minSdk=26`, `targetSdk=35`

## Commandes exécutées

```powershell
gradle testDebugUnitTest --rerun-tasks
gradle test --rerun-tasks
gradle assembleDebug --rerun-tasks
gradle assembleRelease --rerun-tasks
gradle lintRelease --rerun-tasks
```

Les commandes ont été exécutées en mono-worker avec `JAVA_HOME` et `ANDROID_HOME` limités au processus.

## Résultats

- 147 tests distincts ;
- 147/147 réussis en debug ;
- 147/147 réussis en release ;
- 294 exécutions réussies pendant la passe globale forcée ;
- 0 échec, 0 erreur, 0 test ignoré ;
- `assembleDebug --rerun-tasks` : succès ;
- `assembleRelease --rerun-tasks` et lint vital : succès ;
- `lintRelease --rerun-tasks` : succès.

## FRESH EXECUTABLE ENTRY

Les tests vérifient : silence et absence de plan à 0 et 14 999 ms ; snapshot courant obligatoire à 15 000 ms ; `marketableAtCreation` non autorisant ; ask LONG et bid SHORT courants ; LIMIT distante silencieuse ; retour réel sur la LIMIT ; C04/C07/C08/P01/feed stale bloquants ; publication possible à 15 s, 63 s et exactement 120 s ; expiration après 120 s ; target-before-fill classé `MISSED_NO_FILL` sans faux TP ; tombstone anti-résurrection ; déduplication et conservation du premier `createdAt` ; une seule alerte finale.

## DYNAMIC STRUCTURAL STOP

- Cas A : A=1,3105, E60=1,815, R=2,76 donne SL=2,0771 et TP=3,96625, plan valide.
- Cas D : A=3,367, E60=3,115 est refusé par `V2329_STRUCTURAL_STOP_TOO_WIDE`.
- Cas E : A=2,9495, E60=6,365 est refusé par le même reason code.
- Les tests couvrent les trois termes du SL, le SL maximal, l’absence de clamp artificiel, E60 bid/ask symétrique et l’arrondi conservateur LONG/SHORT.

## DYNAMIC MARKET TARGET

- Cas B : A=0,455, E60=0,01, R=1,735 donne SL=0,55, TP=2,80 et riskQuantity=5.
- Cas C : A=0,9225, E60=0,01, R=6,865 donne SL=0,64575, TP=4,0225 et riskQuantity=4.
- Le plancher 2,80, le plafond 5,50, la contribution A/R, le coût central 1,43 et le rejet reward/risk <1,40 sont vérifiés.

## RISK BUDGET SIZING

Les tests couvrent le calcul du risque par ETH, la quantité par budget, le plafond qualité comme maximum uniquement, les quantités 1 et 2 ETH sans remontée à 3, le plafond absolu 7, le rejet budget <1 ETH et la perte théorique bornée. Ils vérifient aussi l’identité entrée/TP/SL/quantité entre plan, écran, notification, diagnostic et persistance.

## RANGE FADE DIAGNOSTIC ONLY

RANGE_FADE LONG et SHORT restent admis dans le journal, y compris avec veto replay comparatif, mais `V2329_RANGE_FADE_DIAGNOSTIC_ONLY` empêche toute publication. Les tests confirment : aucun son, aucun plan actif, aucun blocage du P01 suivant et niveaux théoriques toujours exportables.

## TP/SL ONLY ACTIVE LIFECYCLE

Les suites de non-régression confirment : un seul plan actif ; aucun timeout ou invalidation automatique ; aucune action publique de sortie ; niveaux et quantité immuables ; fin uniquement TP/SL ; nouveau signal autorisé après TP/SL ; persistance/restauration silencieuse ; même ID de notification ; reset diagnostic conservant le plan actif ; état corrompu ignoré ; `realTradingAllowed=false`.

## APK locale

Debug :

- chemin : `C:\Users\Tenni\Documents\Codex\2026-07-25\tu-dois-r-aliser-maintenant-la-2\app\build\outputs\apk\debug\app-debug.apk` ;
- taille : `4 509 716` octets ;
- SHA-256 : `FA058276CE7EEF239AC7ACAE70EA1C656AC5F3A551D1CAC88B7576DE9CF97122` ;
- manifeste vérifié par `aapt` : package `com.ethscalper.cockpit.debug`, versionCode 23290, versionName 2.32.9, minSdk 26, targetSdk 35.

Release locale non signée :

- chemin : `app\build\outputs\apk\release\app-release-unsigned.apk` ;
- taille : `3 591 061` octets ;
- SHA-256 : `33ED68CAD7D458D06734049376B9A76B7865CCE6E4F623C7B22FFBC7E76EDB0E`.

## GitHub Actions

Run du commit applicatif `a892037f6bae5688d776ee53efa9161842a38d6e` :

- run ID : `30211219232` ;
- URL : `https://github.com/ossamatennich/eth-scalper-cockpit-android/actions/runs/30211219232` ;
- job `test-and-build` : succès (tests, build debug et upload) ;
- artefact : `ETH-Scalper-Cockpit-v2.32.9-debug-apk` ;
- artefact ID : `8634509497` ;
- taille de l’archive GitHub : `4 048 042` octets ;
- digest de l’archive : `sha256:ccdcc79938629b6c1f6d99c0e52884c167a3862c1a703937ba85df3f6fd66893` ;
- taille de l’APK extraite : `4 508 924` octets ;
- SHA-256 de l’APK CI : `7F8159450CC0760A4C08A523B30B270C9C21825A9F85152569B4B7D1DBFE20EF` ;
- manifeste CI vérifié par `aapt` : versionCode 23290, versionName 2.32.9, minSdk 26, targetSdk 35.

La différence d’empreinte locale/CI vient des environnements et signatures debug distincts. La PR reste ouverte, en brouillon et non fusionnée ; aucun artefact de release définitive n’a été publié.

## Résultats de recherche fournis

La calibration hors Codex sur 14 sessions et 39 695 frames uniques rapporte 15 opportunités P01 propres, dont 13 trades retenus, 11 TP, 2 SL, +22,2652 USDT/ETH net standardisé, profit factor 4,8414, drawdown maximal 3,4487 USDT/ETH et 9 sessions positives sur 10 tradées.

Découverte : 7 trades, 5 TP, 2 SL, +10,1752 USDT/ETH. Holdout historique : 4 trades, 4 TP, +8,1275 USDT/ETH. Cas récents corrigés : 2 trades, 2 TP, +3,9625 USDT/ETH.

Stress coût 2,00/slippage 0,15 : +12,9052 USDT/ETH. Stress coût 2,145/slippage 0,20 : +10,3702 USDT/ETH. Avec budget 10 USDT : quantités 2–5 ETH, moyenne environ 3,38, perte maximale modélisée environ 9,91 USDT et résultat théorique environ +75,40 USDT.

Ces résultats de recherche sur diagnostics ne garantissent aucune performance ou rentabilité future. Aucun replay indépendant supplémentaire n’a été fabriqué ou revendiqué par Codex.
