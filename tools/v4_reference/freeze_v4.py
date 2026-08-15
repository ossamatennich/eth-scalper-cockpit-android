#!/usr/bin/env python3
"""Write a deterministic SHA-256 inventory of all V4 decision material."""
from __future__ import annotations
import hashlib,json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
FILES=[
 "app/src/main/assets/v4_engine_spec.json","app/src/main/assets/v4_fallback_model.json",
 "app/src/main/assets/v4_model_manifest.json","tools/v4_reference/build_v4_model.py",
 "app/src/main/java/com/ethscalper/cockpit/V4Universe.java",
 "app/src/main/java/com/ethscalper/cockpit/V4DailyBar.java",
 "app/src/main/java/com/ethscalper/cockpit/V4FeatureEngine.java",
 "app/src/main/java/com/ethscalper/cockpit/V4ExtraTreesModel.java",
 "app/src/main/java/com/ethscalper/cockpit/V4Engine.java",
 "app/src/main/java/com/ethscalper/cockpit/V4RiskSizer.java",
 "app/src/main/java/com/ethscalper/cockpit/V4ContinuationPolicy.java",
 "app/src/main/java/com/ethscalper/cockpit/V4Plan.java",
 "app/src/main/java/com/ethscalper/cockpit/V4PlanLifecycle.java",
 "app/src/main/java/com/ethscalper/cockpit/V4MarketDataClient.java",
 "app/src/main/java/com/ethscalper/cockpit/V4RuntimeCoordinator.java",
 "app/src/test/resources/v4_prediction_fixture.json"]
def digest(path:Path)->str:
 # Git normalizes text blobs to LF while Windows may check them out as CRLF.
 # Freeze the canonical repository representation so the same commit verifies
 # identically on Android CI/Linux and on the Windows development workstation.
 data=path.read_bytes().replace(b"\r\n",b"\n")
 h=hashlib.sha256();h.update(data);return h.hexdigest()
if __name__=="__main__":
 out=ROOT/"app/src/main/assets/v4_frozen_hash_manifest.json"
 payload={"schema":"NMC_PROP_DAILY_HYBRID_V4_FREEZE","engineId":"NMC_PROP_DAILY_HYBRID_V4","realTradingAllowed":False,
          "files":[{"path":p,"sha256":digest(ROOT/p)} for p in FILES]}
 out.write_text(json.dumps(payload,indent=2,sort_keys=True)+"\n",encoding="utf-8");print(digest(out))
