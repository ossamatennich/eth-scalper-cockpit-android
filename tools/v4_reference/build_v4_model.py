#!/usr/bin/env python3
"""Deterministic NMC_PROP_DAILY_HYBRID_V4 fallback-model builder.

Consumes the already validated Binance USD-M daily panel, restricts targets to
2023-01-01..2025-12-31, and emits the compact tree representation used by the
Android evaluator. Raw market data is never copied into the APK.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import ExtraTreesRegressor

FEATURES = ["ret1", "ret3", "ret5", "ret7", "ret14", "ret21", "of3", "of7",
            "buy_ratio", "qvz7", "qvz30", "atr14", "loc14", "loc21"]
UNIVERSE = "AAVE ADA AIXBT ALGO APT ARB ASTER ATOM AVAX BCH BNB BTC CRV DOGE DOT ETC ETH FARTCOIN FIL GRASS HBAR HYPE INJ JTO JUP KAITO LDO LINK LTC MOODENG NEAR ONDO OP PENGU PNUT POL POPCAT PUMP RENDER S SOL STX SUI TAO TIA TRUMP TRX UNI VIRTUAL WIF WLD XPL XRP".split()


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def load_panel(paths: list[Path]) -> pd.DataFrame:
    frames = []
    for path in paths:
        x = pd.read_csv(path)
        x = x.rename(columns={"symbol": "asset", "quoteVolume": "quote_volume",
                              "takerBuyQuote": "taker_buy_quote"})
        x["date"] = pd.to_datetime(x["date"], utc=True)
        frames.append(x)
    df = pd.concat(frames, ignore_index=True)
    df = df[df.asset.isin(UNIVERSE)].drop_duplicates(["asset", "date"])
    return df.sort_values(["asset", "date"]).reset_index(drop=True)


def engineer(df: pd.DataFrame) -> pd.DataFrame:
    out = []
    for asset, g in df.groupby("asset", sort=True):
        g = g.copy().sort_values("date")
        close, prev = g.close.astype(float), g.close.shift(1).astype(float)
        tr = pd.concat([(g.high-g.low).abs(), (g.high-prev).abs(), (g.low-prev).abs()], axis=1).max(axis=1)
        atr = tr.rolling(14, min_periods=14).mean()
        qlog = np.log(g.quote_volume.astype(float).clip(lower=1e-12))
        buy = g.taker_buy_quote.astype(float).clip(lower=0)
        sell = (g.quote_volume.astype(float)-buy).clip(lower=0)
        eps = 1e-12*np.maximum(1.0, g.quote_volume.astype(float))
        of1 = np.log(buy+eps)-np.log(sell+eps)
        for n in (1,3,5,7,14,21): g[f"ret{n}"] = close/close.shift(n)-1
        g["of3"], g["of7"] = of1.rolling(3).sum(), of1.rolling(7).sum()
        g["buy_ratio"] = buy/np.maximum(g.quote_volume.astype(float), eps)
        for n in (7,30):
            mean, std = qlog.rolling(n).mean(), qlog.rolling(n).std(ddof=0)
            g[f"qvz{n}"] = (qlog-mean)/std.replace(0,np.nan)
        g["atr_abs"], g["atr14"] = atr, atr/close
        for n in (14,21):
            lo, hi = g.low.rolling(n).min(), g.high.rolling(n).max()
            g[f"loc{n}"] = (close-lo)/(hi-lo).replace(0,np.nan)
        g["qv30"] = g.quote_volume.rolling(30).mean()
        g["history"] = np.arange(len(g))+1
        g["next_open"], g["next_high"], g["next_low"], g["next_close"] = (
            g.open.shift(-1), g.high.shift(-1), g.low.shift(-1), g.close.shift(-1))
        out.append(g)
    x = pd.concat(out, ignore_index=True)
    x["qv_rank"] = x.groupby("date").qv30.rank(pct=True, method="average")
    eligible = (x.history >= 90) & (x.qv_rank >= .40)
    for f in FEATURES:
        mean = x[f].where(eligible).groupby(x.date).transform("mean")
        std = x[f].where(eligible).groupby(x.date).transform(lambda s: s.std(ddof=0))
        x[f] = (x[f]-mean)/std.replace(0,np.nan)
    entry, atr = x.next_open.astype(float), x.atr_abs.astype(float)
    long_tp, long_sl = entry+2*atr, entry-atr
    short_tp, short_sl = entry-2*atr, entry+atr
    # Conservative and deterministic SL-first daily-bar ambiguity.
    long_exit = np.where(x.next_low <= long_sl, long_sl,
                         np.where(x.next_high >= long_tp, long_tp, x.next_close))
    short_exit = np.where(x.next_high >= short_sl, short_sl,
                          np.where(x.next_low <= short_tp, short_tp, x.next_close))
    x["long_target"] = long_exit/entry-1-.001-.00033
    x["short_target"] = 1-short_exit/entry-.001-.00033
    return x[eligible & (x.date <= pd.Timestamp("2025-12-31", tz="UTC"))]


def tree_json(model: ExtraTreesRegressor) -> list[dict]:
    trees = []
    for est in model.estimators_:
        t = est.tree_
        trees.append({"left": t.children_left.tolist(), "right": t.children_right.tolist(),
                      "feature": t.feature.tolist(), "threshold": [float(v) for v in t.threshold],
                      "value": [float(v) for v in t.value[:,0,0]]})
    return trees


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--panel", action="append", required=True)
    ap.add_argument("--output", required=True)
    ap.add_argument("--manifest", required=True)
    ap.add_argument("--fixture")
    args = ap.parse_args()
    inputs = [Path(p).resolve() for p in args.panel]
    frame = engineer(load_panel(inputs)).dropna(subset=FEATURES+["long_target","short_target"])
    params = dict(n_estimators=120, max_depth=5, min_samples_leaf=100,
                  max_features=.7, random_state=23455, n_jobs=1)
    models, fitted = {}, {}
    X = frame[FEATURES].to_numpy(dtype=np.float64)
    for side in ("long", "short"):
        model = ExtraTreesRegressor(**params).fit(X, frame[f"{side}_target"].to_numpy())
        models[side] = tree_json(model); fitted[side] = model
    output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
    payload = {"schema":"NMC_PROP_DAILY_HYBRID_V4_EXTRATREES","modelVersion":"4.0.0",
               "engineId":"NMC_PROP_DAILY_HYBRID_V4","trainingStart":"2023-01-01",
               "trainingEnd":"2025-12-31","featureOrder":FEATURES,"hyperparameters":params,
               "trainingRows":len(frame),"trainingAssets":sorted(frame.asset.unique().tolist()),
               "long":models["long"],"short":models["short"]}
    output.write_text(json.dumps(payload,separators=(",",":"),allow_nan=False), encoding="utf-8")
    manifest = {"schema":"NMC_V4_MODEL_MANIFEST","engineId":payload["engineId"],
                "modelSha256":sha256(output),"trainingCodeSha256":sha256(Path(__file__)),
                "modelAsset":output.name,"trainingDateRange":["2023-01-01","2025-12-31"],
                "featureOrder":FEATURES,"hyperparameters":params,"trainingRows":len(frame),
                "sources":[{"path":p.name,"sha256":sha256(p)} for p in inputs]}
    mp = Path(args.manifest); mp.parent.mkdir(parents=True,exist_ok=True)
    mp.write_text(json.dumps(manifest,indent=2,sort_keys=True),encoding="utf-8")
    if args.fixture:
        sample=frame.sort_values(["date","asset"]).head(12)
        fixture={"schema":"NMC_V4_PREDICTION_FIXTURE","featureOrder":FEATURES,"rows":[]}
        for _,row in sample.iterrows():
            values=[float(row[f]) for f in FEATURES]
            fixture["rows"].append({"asset":row.asset,"date":row.date.isoformat(),"features":values,
                                    "long":float(fitted["long"].predict([values])[0]),
                                    "short":float(fitted["short"].predict([values])[0])})
        fixture_path=Path(args.fixture);fixture_path.parent.mkdir(parents=True,exist_ok=True)
        fixture_path.write_text(json.dumps(fixture,indent=2,allow_nan=False),encoding="utf-8")
    print(json.dumps({"rows":len(frame),"assets":frame.asset.nunique(),"modelSha256":manifest["modelSha256"]}))


if __name__ == "__main__": main()
