# Candidate v2.34.0 — Test report

## Baseline

Avant modification : 210 tests distincts debug et 210 release, zéro échec, erreur ou test ignoré. Worktree propre, HEAD/branche/PR/main conformes aux références verrouillées.

## Couverture ajoutée

- golden master ETH : 20 000 snapshots déterministes, graine `23321042`, parité bit à bit ;
- profils et registre ; rejet des symboles inconnus ; faux troisième profil ;
- scaling SOL à la référence, moitié et double du prix ;
- budgets qualité 3–7 et quantités cohérentes ;
- symétrie LONG/SHORT, quotes invalides ;
- deux plans simultanés, blocage intra-symbole, terminaux et réarmements indépendants ;
- persistance/restauration/corruption isolées et migration ETH historique ;
- signatures distinctes, BTC contexte uniquement, OLS60 SOL ;
- RANGE_FADE diagnostic-only et `realTradingAllowed=false`.

Total final attendu et vérifié par les résultats JUnit : **245 tests distincts par variante**, dont les 210 historiques.

## Validation officielle SOL

- Source : archives publiques Binance Futures USD-M avec CHECKSUM SHA-256.
- Période : 2024-07-01 au 2026-07-26.
- Archives : 150 ZIP.
- Bougies : 1 088 640 par symbole ETHUSDT/SOLUSDT/BTCUSDT.
- Trous : 0 ; doublons identiques : 0 ; conflits : 0.
- Manifest SHA-256 : `72ae835456076e828ab7f2a60d24decf2ae40a92f2357bb1dc2323c02ef2b626`.
- Ratio relatif SOL/ETH : p10 0.957939 ; p25 1.098397 ; médiane 1.283498 ; p75 1.528929 ; p90 1.835800.
- Corrélation rendements 1m ETH/SOL : 0.768061.
- Contrôle médiane [0.85 ; 1.35] : PASS.

Les métadonnées finales des commandes Gradle, APK locale et CI sont complétées dans la description de la PR après le run, afin de ne pas créer de second commit documentaire.

## Résultats techniques locaux

- `testDebugUnitTest --rerun-tasks` : PASS, 245 tests, 0 échec, 0 erreur, 0 ignoré.
- `test --rerun-tasks` : PASS, 245 debug + 245 release, soit 490 exécutions, toutes vertes.
- `assembleDebug` : PASS.
- `assembleRelease` : PASS.
- `lintRelease` : PASS, 0 erreur (66 avertissements historiques/non bloquants).
- Debug : `app/build/outputs/apk/debug/app-debug.apk`, 4 550 804 octets, SHA-256 `9b70225cc95899d59b6509f51e73f449293e69307b9f31e1197745a5609f5416`.
- Release non signée : `app/build/outputs/apk/release/app-release-unsigned.apk`, 3 623 841 octets, SHA-256 `15334cdf2fbaf33107921a60e9a3af80a8b21f17f507de9ddbba8402bb5345e3`.
- Manifeste debug : package `com.ethscalper.cockpit.debug`, versionCode 23400, versionName 2.34.0, minSdk 26, target/compileSdk 35.
- Manifeste release : package `com.ethscalper.cockpit`, versionCode 23400, versionName 2.34.0, minSdk 26, target/compileSdk 35.

Les métadonnées finales du run et de l’artefact CI sont ajoutées à la description de la PR après le run afin de conserver l’unique commit demandé.
