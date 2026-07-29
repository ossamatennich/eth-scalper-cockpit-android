# Régression des diagnostics naturels NMC récents

## Corpus

- `NMC_Diagnostic_v2_34_1_1_20260728_220444.zip` — 238 839 octets — SHA-256 `70879e2681326ef0b0184ed67ddeef6a1e829312ced01fda371baea8f78f73e2`
- `NMC_Diagnostic_v2_34_1_1_20260728_220647.zip` — 69 711 octets — SHA-256 `3d4835b16ee34431ec7dc0e5efe0001cae012dd08d267ab0440a7f23ae020eaf`

## Quatre plans observés

1. SOL P02 LONG, entrée 74,25, TP 74,42, SL 74,18, score 80 : `SL_TOUCHED`.
2. ETH P02 LONG, entrée 1 920,46, TP 1 923,78, SL 1 919,24, score 86 : `SL_TOUCHED`.
3. SOL P01 LONG anticipé, entrée 74,23, TP 74,43, SL 74,16, score 96 : publication ancienne à environ 2,013 s, puis `SL_TOUCHED`.
4. ETH P01 LONG anticipé, entrée 1 920,94, TP 1 924,77, SL 1 919,67, score 96 : publication ancienne à environ 1,492 s, puis `SL_TOUCHED` vers 25,968 s.

La v2.34.2 transforme les publications nº 3 et nº 4 en diagnostics shadow avant 15 secondes : aucun plan, aucune persistance, aucune sonnerie. Pour le cas SOL, la frame vers 15,453 s est encore exécutable (ask 74,23) mais échoue le filtre public précoce v2.33.1 : `m1=1,0448`, `m3=1,6418`, `f30=0,0846`, `room=1,6418`; ni `flowBacked` ni `priceLed` n’est vrai. Le corpus se termine avant une observation contrefactuelle complète jusqu’à 90 s sans le verrou de l’ancien plan. Il serait donc incorrect de fabriquer un nouveau terminal.

Les P02 publics, leurs niveaux et leur lifecycle ne changent pas. Le rapport conserve aussi comme axes de recherche les rejets `PRIX_DEJA_TROP_LOIN`; aucun seuil n’est recalibré à partir de ces deux sessions.

Conclusion : la régression confirme la suppression de deux publications trop précoces connues. Elle ne revendique pas que ces refus deviennent des gagnants et ne constitue aucune garantie financière.
