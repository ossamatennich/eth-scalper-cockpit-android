# NMC v2.34.3.8 — diagnostic complet et alerte sonore finale

## Défauts corrigés

Le statut minimal de sécurité de la v2.34.3.7 maintenait les prix visibles mais omettait l’index
du recorder et les événements récents. L’écran affichait donc « Index indisponible » alors que le
flux de marché était valide.

Le chemin d’alerte considérait également `NotificationManager.notify()` comme une livraison
sonore réussie même lorsque le canal Android était désactivé, trop faible ou privé de son. La
signature métier pouvait alors être consommée sans alerte audible.

## Correction diagnostic

- le statut de secours inclut l’index, les vingt événements récents, les plans actifs et la santé
  du canal sonore ;
- les sections facultatives de recherche sont isolées : leur erreur ne remplace plus le statut
  complet ;
- l’interface indique le mode complet ou de secours et l’erreur exacte ;
- une erreur de sérialisation est enregistrée de manière bornée dans les diagnostics.

## Correction alerte

- nouveau canal `nmc_final_signal_loud_v2` avec importance haute, son embarqué, usage alarme et
  vibration forte ;
- contrôle `CHANNEL_READY` obligatoire avant publication et avant écriture de déduplication ;
- si le canal n’est pas prêt, le plan reste affiché silencieusement sans consommer sa signature ;
- trois nouvelles tentatives bornées à 5 s, 30 s et 120 s ;
- avant une nouvelle tentative prête, la notification silencieuse provisoire est retirée afin que
  la publication sur le canal sonore soit bien traitée comme une nouvelle alerte Android ;
- le test sonore et les signaux réels utilisent toujours le même chemin central.

## Invariants

Aucune sélection, formule, entrée, TP, SL, quantité, persistance ou règle de lifecycle n’est
modifiée. Les restaurations et terminaux restent silencieux. `realTradingAllowed=false`.
