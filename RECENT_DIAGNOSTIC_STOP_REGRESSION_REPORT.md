# Régression stop — diagnostics naturels récents

Corpus : les deux ZIP naturels NMC v2.34.1.1 vérifiés et déjà documentés dans v2.34.2.0.

| Cas | Sélection restaurée | Stop ancien | Base / structure | Stop v2.34.3 | Qté ancienne | Budget / Qté nouvelle | Terminal contrefactuel |
|---|---|---:|---|---:|---:|---|---|
| SOL P02 LONG 74,25 | survit | 0,07 | A=0,063, E=0, aucune ancre valide (seulement 2 minutes terminées) | 0,07 | 65 | 10,00 / 58 SOL | SL, niveaux inchangés |
| ETH P02 LONG 1 920,46 | survit | 1,22 | A=1,2155, E=0,14, aucune ancre cohérente 5m | 1,22 | 3 | 12,00 / 3 ETH | SL, résultat réalisé inchangé -7,95 USDT |
| SOL P01 LONG 74,23 publié à ~2,013 s | interdit avant 15 s ; échec du filtre public vers 15,453 s | 0,07 ancien | non applicable : aucun plan final | — | 58 ancienne | aucun budget public / aucune quantité | aucun trade |
| ETH P01 LONG 1 920,94 publié à ~1,492 s | shadow puis bloqué par le lifecycle restauré/réarmement | 1,27 ancien | non applicable : aucun plan final | — | 3 ancienne | aucun budget public / aucune quantité | aucun trade |

Les MFE/MAE historiques restent diagnostics : le P02 ETH a atteint une MFE d’environ 2,415 avant son SL, sans toucher son TP de 3,32. Aucun stop n’a été élargi pour transformer ces cas en gagnants. Les cas P01 précoces ne sont pas fabriqués à 15 secondes. `PRIX_DEJA_TROP_LOIN` et les seuils SOL ne sont pas modifiés.
