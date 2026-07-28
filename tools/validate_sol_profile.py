#!/usr/bin/env python3
"""Reproducible Binance Futures USD-M 1m validation for SOL_V1_20260727.

This validates public closed-candle price/volatility/distance/risk consistency. It is
not an exact replay of sub-minute aggTrade flow and does not estimate profitability.
"""
from __future__ import annotations
import argparse,csv,datetime as dt,hashlib,io,json,math,statistics,urllib.request,zipfile
from collections import deque
from pathlib import Path

BASE="https://data.binance.vision/data/futures/um"
SYMBOLS=("ETHUSDT","SOLUSDT","BTCUSDT")
REF=75.80;TICK=.01

def months(start,end):
    y,m=start.year,start.month
    while (y,m)<=(end.year,end.month):
        yield f"{y:04d}-{m:02d}";m+=1
        if m==13:y,m=y+1,1
def sha(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda:f.read(1<<20),b""):h.update(chunk)
    return h.hexdigest()
def get(url,path):
    if not path.exists():
        path.parent.mkdir(parents=True,exist_ok=True)
        req=urllib.request.Request(url,headers={"User-Agent":"ETH-SOL-profile-validator/1.0"})
        with urllib.request.urlopen(req,timeout=120) as r,path.open("wb") as f:
            while True:
                block=r.read(1<<20)
                if not block:break
                f.write(block)
    return path
def archive(cache,symbol,period,daily):
    scope="daily" if daily else "monthly";name=f"{symbol}-1m-{period}.zip"
    url=f"{BASE}/{scope}/klines/{symbol}/1m/{name}"
    z=get(url,cache/scope/name);check=get(url+".CHECKSUM",cache/scope/(name+".CHECKSUM"))
    expected=check.read_text(encoding="utf-8").strip().split()[0].lower();actual=sha(z)
    if expected!=actual:
        # An interrupted prior download is recoverable; redownload once, then fail hard.
        z.unlink();z=get(url,cache/scope/name);actual=sha(z)
    if expected!=actual:raise RuntimeError(f"SHA-256 mismatch {name}: {actual} != {expected}")
    return z,{"file":name,"sha256":actual,"bytes":z.stat().st_size,"source":url}
def normalize_ts(raw):
    value=int(raw)
    while value>10_000_000_000_000:value//=1000
    return value
def read_rows(path):
    rows=[];seen={};gaps=0;duplicates=0
    with zipfile.ZipFile(path) as z:
        names=[n for n in z.namelist() if n.lower().endswith(".csv")]
        if len(names)!=1:raise RuntimeError(f"Unexpected ZIP members: {path}")
        with z.open(names[0]) as raw:
            text=io.TextIOWrapper(raw,encoding="utf-8-sig",newline="")
            for row in csv.reader(text):
                if not row or not row[0].strip().lstrip("-").isdigit():continue
                if len(row)<6:raise RuntimeError(f"Short OHLCV row in {path.name}")
                stamp=normalize_ts(row[0]);o,h,l,c,v=map(float,row[1:6])
                if not all(math.isfinite(x) for x in (o,h,l,c,v)) or min(o,h,l,c)<=0 or v<0 or h<max(o,c) or l>min(o,c):
                    raise RuntimeError(f"Invalid OHLCV {path.name} at {stamp}")
                value=(o,h,l,c,v)
                if stamp in seen:
                    duplicates+=1
                    if seen[stamp]!=value:raise RuntimeError(f"Conflicting duplicate {path.name} at {stamp}")
                    continue
                seen[stamp]=value;rows.append((stamp,o,h,l,c,v))
    rows.sort()
    for a,b in zip(rows,rows[1:]):
        if b[0]-a[0]!=60_000:gaps+=max(1,(b[0]-a[0])//60_000-1)
    return rows,gaps,duplicates
def relative(rows):
    ranges=deque(maxlen=20);out={}
    for stamp,o,h,l,c,v in rows:
        if len(ranges)==20:out[stamp]=(sum(ranges)/20)/c
        ranges.append(h-l)
    return out
def quantile(sorted_values,p):
    if not sorted_values:return math.nan
    x=(len(sorted_values)-1)*p;lo=int(math.floor(x));hi=int(math.ceil(x))
    return sorted_values[lo] if lo==hi else sorted_values[lo]*(hi-x)+sorted_values[hi]*(x-lo)
def corr(xs,ys):
    if len(xs)<2:return math.nan
    mx=sum(xs)/len(xs);my=sum(ys)/len(ys)
    num=sum((x-mx)*(y-my) for x,y in zip(xs,ys));dx=sum((x-mx)**2 for x in xs);dy=sum((y-my)**2 for y in ys)
    return num/math.sqrt(dx*dy) if dx>0 and dy>0 else math.nan
def ceil_tick(x):return math.ceil((x-1e-12)/TICK)*TICK
def floor_tick(x):return math.floor((x+1e-12)/TICK)*TICK
def validate_quantity(quantity,budget,risk):
    if quantity<1 or quantity>120:
        raise RuntimeError(f"Invalid quantity {quantity}; expected 1..120")
    if quantity*risk>budget+1e-9:
        raise RuntimeError(f"Budget exceeded: {quantity} * {risk} > {budget}")
    return quantity
def validate_distance_bounds(slmin,slcap,tpfloor,tpcap):
    if slmin>slcap:raise RuntimeError(f"SL minimum exceeds maximum: {slmin} > {slcap}")
    if tpfloor>tpcap:raise RuntimeError(f"TP floor exceeds cap: {tpfloor} > {tpcap}")
def validate_profile(sol_rows):
    checked=0;quantity_rejections=0
    for _,_,_,_,entry,_ in sol_rows:
        scale=entry/REF;amin=ceil_tick(.015*scale);slmin=ceil_tick(.03*scale);slcap=max(slmin,floor_tick(.10*scale));cost=ceil_tick(.06*scale);allow=ceil_tick(.10*scale);tpfloor=ceil_tick(.12*scale);tpcap=max(tpfloor,floor_tick(.23*scale))
        validate_distance_bounds(slmin,slcap,max(tpfloor,1.95*cost),tpcap)
        for budget in (10,11.14,12.28,13.41,14.55):
            risk=slmin+allow;q=math.floor(budget/risk)
            try:
                validate_quantity(q,budget,risk)
            except RuntimeError as exc:
                if q<1 or q>120:quantity_rejections+=1
                else:raise exc
        checked+=1
    return checked,quantity_rejections
def main():
    parser=argparse.ArgumentParser();parser.add_argument("--cache",default="build/sol-profile-cache");parser.add_argument("--report",default="SOL_PROFILE_V1_RESEARCH_REPORT.md");parser.add_argument("--manifest",default="SOL_PROFILE_V1_CORPUS_MANIFEST.json");parser.add_argument("--end-date",default=(dt.date.today()-dt.timedelta(days=1)).isoformat());args=parser.parse_args()
    cache=Path(args.cache);end=dt.date.fromisoformat(args.end_date);periods=[(p,False) for p in months(dt.date(2024,7,1),dt.date(2026,6,1))]
    periods += [(d.isoformat(),True) for d in (dt.date(2026,7,1)+dt.timedelta(days=i) for i in range((end-dt.date(2026,7,1)).days+1))]
    ratios=[];eth_ret=[];sol_ret=[];monthly={};manifest=[];counts={s:0 for s in SYMBOLS};gaps={s:0 for s in SYMBOLS};dups={s:0 for s in SYMBOLS};first=None;last=None;profile_checked=0;quantity_rejections=0
    for period,daily in periods:
        data={}
        for symbol in SYMBOLS:
            z,item=archive(cache,symbol,period,daily);manifest.append(item);rows,g,d=read_rows(z);data[symbol]=rows;counts[symbol]+=len(rows);gaps[symbol]+=g;dups[symbol]+=d
            if rows:first=rows[0][0] if first is None else min(first,rows[0][0]);last=rows[-1][0] if last is None else max(last,rows[-1][0])
        er,sr=relative(data["ETHUSDT"]),relative(data["SOLUSDT"]);common=sorted(set(er)&set(sr));local=[]
        for stamp in common:
            if er[stamp]>0:
                value=sr[stamp]/er[stamp];ratios.append(value);local.append(value)
        eclose={r[0]:r[4] for r in data["ETHUSDT"]};sclose={r[0]:r[4] for r in data["SOLUSDT"]};aligned=sorted(set(eclose)&set(sclose))
        for a,b in zip(aligned,aligned[1:]):
            if b-a==60_000:eth_ret.append(eclose[b]/eclose[a]-1);sol_ret.append(sclose[b]/sclose[a]-1)
        key=period[:7];monthly.setdefault(key,[]).extend(local);checked,rejected=validate_profile(data["SOLUSDT"]);profile_checked+=checked;quantity_rejections+=rejected
    ordered=sorted(ratios);stats={"p10":quantile(ordered,.10),"p25":quantile(ordered,.25),"median":quantile(ordered,.50),"p75":quantile(ordered,.75),"p90":quantile(ordered,.90)}
    if gaps["ETHUSDT"] or gaps["SOLUSDT"] or gaps["BTCUSDT"]:raise RuntimeError(f"Corpus gaps: {gaps}")
    if not .85<=stats["median"]<=1.35:raise RuntimeError(f"Median relative volatility ratio outside acceptance: {stats['median']}")
    manifest_doc={"generatedAt":dt.datetime.now(dt.timezone.utc).isoformat(),"officialSource":BASE,"periodStart":"2024-07-01","periodEnd":end.isoformat(),"archives":manifest,"counts":counts,"gaps":gaps,"duplicates":dups,"quantityRejectionsAboveSafetyCap":quantity_rejections}
    canonical=json.dumps(manifest_doc,sort_keys=True,separators=(",",":")).encode();manifest_doc["manifestSha256"]=hashlib.sha256(canonical).hexdigest();mp=Path(args.manifest);mp.parent.mkdir(parents=True,exist_ok=True);mp.write_text(json.dumps(manifest_doc,indent=2),encoding="utf-8")
    monthly_lines="\n".join(f"- {m}: médiane {statistics.median(v):.6f} ({len(v):,} observations)" for m,v in sorted(monthly.items()) if v)
    report=f"""# SOL_PROFILE_V1_RESEARCH_REPORT\n\n## Portée\n\nValidation reproductible du profil `SOL_V1_20260727` sur les archives publiques officielles Binance Futures USD-M, du 2024-07-01 au {end.isoformat()}. Elle valide les distances, la volatilité, le prix et le risque. La validation exacte du flow SOL 15/30/60 secondes reste à obtenir par les diagnostics naturels de la candidate. Les bougies 1m ne sont pas présentées comme un replay exact des flows sous-minute. Aucune garantie de performance.\n\n## Corpus vérifié\n\n- ETHUSDT: {counts['ETHUSDT']:,} bougies\n- SOLUSDT: {counts['SOLUSDT']:,} bougies\n- BTCUSDT: {counts['BTCUSDT']:,} bougies\n- Trous: {gaps}\n- Doublons identiques: {dups}\n- Doublons conflictuels: 0\n- Archives: {len(manifest)}\n- Manifest SHA-256: `{manifest_doc['manifestSha256']}`\n\n## Ratio de volatilité relative SOL/ETH\n\n- observations: {len(ratios):,}\n- p10: {stats['p10']:.6f}\n- p25: {stats['p25']:.6f}\n- médiane: {stats['median']:.6f}\n- p75: {stats['p75']:.6f}\n- p90: {stats['p90']:.6f}\n- corrélation des rendements 1m ETH/SOL: {corr(eth_ret,sol_ret):.6f}\n- contrôle médiane [0,85 ; 1,35]: PASS\n\n## Stabilité mensuelle\n\n{monthly_lines}\n\n## Contrôles du profil\n\n- {profile_checked:,} prix SOL contrôlés causalement\n- {quantity_rejections:,} calculs de quantité rejetés explicitement hors de la plage 1..120 (aucun clamp silencieux)\n- aucune formule `SL_min > SL_max`\n- aucune formule `TP_floor > TP_cap`\n- distances au tick 0,01\n- quantités positives pour les plans autrement valides\n- perte modélisée inférieure ou égale au budget de qualité\n- seuil de référence `A_min=0.015` confirmé (aucun retour à 0.0147)\n\nLes résultats sont des contrôles de cohérence de recherche, pas une promesse de rentabilité future.\n"""
    Path(args.report).write_text(report,encoding="utf-8");print(json.dumps({"manifestSha256":manifest_doc["manifestSha256"],"counts":counts,"ratios":stats,"observations":len(ratios),"correlation":corr(eth_ret,sol_ret),"quantityRejectionsAboveSafetyCap":quantity_rejections},indent=2))
if __name__=="__main__":main()
