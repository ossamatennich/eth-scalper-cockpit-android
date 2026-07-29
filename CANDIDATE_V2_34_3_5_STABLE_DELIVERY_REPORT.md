# NMC v2.34.3.5 — stable Android delivery

## Root cause

The previous GitHub Actions debug APKs were signed by the runner's temporary debug
keystore. Their certificate SHA-256 values differed between v2.34.3.2, v2.34.3.3
and v2.34.3.4. Android therefore could not install those files as updates of one
another. A phone could keep launching the older build even after a newer APK had
been downloaded.

## Correction

The new `stable` build has:

- the independent package `com.ethscalper.cockpit.stable`;
- the visible label `NMC Stable`;
- a durable signing key injected only through GitHub Actions secrets;
- the artifact name `NMC-v2.34.3.5-stable-apk`;
- explicit CI verification of package metadata and the signing certificate.

The private signing key is never committed. Future stable APKs use the same key and
can update this installation normally. The independent package also avoids asking
the user to uninstall the older debug application before this one-time migration.

## Market behavior

No signal rule, threshold, timing, entry, TP, SL, quantity, lifecycle, persistence,
Binance Futures endpoint, or automatic-order safety setting is changed. The
v2.34.3.4 startup fix remains: primary market I/O starts before diagnostic work and
diagnostic storage cannot block the market handler.

This research application still has `realTradingAllowed=false`; no future result is
guaranteed.
