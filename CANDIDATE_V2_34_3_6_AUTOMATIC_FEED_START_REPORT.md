# NMC v2.34.3.6 — automatic market-feed startup

## Confirmed symptom

On a fresh launch, all ETH, SOL and BTC values could remain empty. Pressing
`Réinitialiser diagnostic` made the feeds appear. The reset did not repair Binance:
it sent a second foreground-service command after Android had finished displaying
its permission and battery dialogs.

## Root cause and correction

The original activity issued its only effective startup command while Android was
opening first-run system UI. If that foreground-service start was delayed or
rejected, no application-level retry existed. The service health loop could only
help after the service had successfully started.

The correction is independent of diagnostics:

- the service is started before requesting notification permission;
- startup is retried after the permission response and every activity resume;
- while the foreground UI is visible, a bounded 1.5/5/12/30-second recovery loop
  continues until a complete fresh feed is reported;
- each service start immediately requests public REST quotes instead of waiting for
  the first health-check tick;
- WebSocket Futures remains the primary authoritative feed and existing REST routes
  remain fallbacks;
- reset still only resets diagnostics and is no longer required to start prices.

No signal, filter, entry, TP, SL, quantity, persistence, terminal, alert or
`realTradingAllowed=false` behavior is changed.
