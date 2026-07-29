# NMC v2.34.3.1 — rapport de tests

La couverture ciblée vérifie les stops LONG/SHORT, la dominance structure/volatilité/excursion, le buffer spread/tick, l’absence de look-ahead, le refus sur budget brut, la séparation des frais, les profils ETH/SOL, le pas de quantité, le TP dynamique inchangé, le refus R/R, l’interface, l’isolation des runtimes et le lifecycle TP/SL uniquement.

Résultats locaux :

- `testDebugUnitTest --rerun-tasks` : 373 tests, 0 échec, 0 erreur, 0 ignoré ;
- `test --rerun-tasks` : 373 tests debug + 373 tests release, tous réussis ;
- `python3 -m unittest tools/test_validate_sol_profile.py` : 9 tests réussis ;
- `assembleDebug` : réussi ;
- `assembleRelease` : réussi ;
- `lintRelease` : réussi, 0 erreur (18 avertissements non bloquants existants).

APK debug locale : 4 098 870 octets, SHA-256 `7fc1a3a43c6d3bad24481d28ecacb220457920cd0cde917ac51c3ea879f5943d`.

Aucun replay historique n’est exécuté par cette candidate. Les métadonnées GitHub Actions sont ajoutées à la pull request après le run.
