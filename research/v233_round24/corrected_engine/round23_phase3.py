#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, math, sys
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal
import numpy as np
import pandas as pd

HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(HERE))
import round23_engine as e
import round23_phase2 as p2
Side=Literal['LONG','SHORT']

@dataclass(frozen=True)
class P3Spec:
    architecture:str
    side:Side
    regime_threshold:float=.15
    h4_adx_min:float=16.
    channel_bars:int=12
    range_atr_min:float=1.2
    body_atr_min:float=.5
    close_location_min:float=.7
    rel_volume_min:float=1.
    atr_stop_mult:float=1.3
    atr_stop_cap:float=3.
    reward_risk:float=2.2
    partial_r:float=99.
    partial_fraction:float=0.
    time_decay_bars:int=99
    minimum_favourable_r:float=0.
    protect_trigger_r:float=99.
    protect_stop_r:float=-1.
    breakeven_trigger_r:float=99.
    max_hold_bars:int=16
    cooldown_bars:int=4
    risk_pct:float=.0015
    max_leverage:float=1.

def verify_lock(path:Path)->str:
    got=hashlib.sha256(path.read_bytes()).hexdigest();exp=path.with_suffix('.sha256').read_text().split()[0]
    if got!=exp: raise RuntimeError(f'lock mismatch {path.name}')
    return got

def central_specs():
    out=[]
    for side in ('LONG','SHORT'):
        out += [
          P3Spec('DIRECTIONAL_RANGE_EXPANSION_SCALEOUT_ONLY',side,partial_r=.6,partial_fraction=.5,reward_risk=2.2,max_hold_bars=16),
          P3Spec('DIRECTIONAL_RANGE_EXPANSION_RISK_SHAPED',side,partial_r=.6,partial_fraction=.5,reward_risk=2.2,time_decay_bars=5,minimum_favourable_r=.25,max_hold_bars=16),
          P3Spec('DIRECTIONAL_RANGE_EXPANSION_EARLY_PROTECT',side,reward_risk=2.2,protect_trigger_r=.5,protect_stop_r=-.25,breakeven_trigger_r=.8,max_hold_bars=16),
        ]
    return out

def neighbours(s:P3Spec):
    out=[s]
    def add(k,vals):
        nonlocal out
        for v in vals: out.append(replace(s,**{k:v}))
    if s.architecture=='DIRECTIONAL_RANGE_EXPANSION_SCALEOUT_ONLY':
        add('partial_r',[.5,.7]);add('partial_fraction',[.33,.67]);add('reward_risk',[2.,2.4]);add('max_hold_bars',[12,20]);add('range_atr_min',[1.05,1.35]);add('channel_bars',[8,20])
    elif s.architecture=='DIRECTIONAL_RANGE_EXPANSION_RISK_SHAPED':
        add('partial_r',[.5,.7]);add('partial_fraction',[.33,.67]);add('reward_risk',[2.,2.4]);add('time_decay_bars',[4,7]);add('minimum_favourable_r',[.15,.35]);add('max_hold_bars',[12,20])
    elif s.architecture=='DIRECTIONAL_RANGE_EXPANSION_EARLY_PROTECT':
        add('protect_trigger_r',[.4,.6]);add('protect_stop_r',[-.4,-.1]);add('breakeven_trigger_r',[.7,1.]);add('reward_risk',[2.,2.4]);add('max_hold_bars',[12,20]);add('channel_bars',[8,20])
    return list({json.dumps(asdict(x),sort_keys=True):x for x in out}.values())

def to_p2(s:P3Spec)->p2.P2Spec:
    return p2.P2Spec(
      architecture='DIRECTIONAL_RANGE_EXPANSION',side=s.side,regime_threshold=s.regime_threshold,h4_adx_min=s.h4_adx_min,
      channel_bars=s.channel_bars,range_atr_min=s.range_atr_min,body_atr_min=s.body_atr_min,close_location_min=s.close_location_min,
      rel_volume_min=s.rel_volume_min,atr_stop_mult=s.atr_stop_mult,atr_stop_cap=s.atr_stop_cap,reward_risk=s.reward_risk,
      max_hold_bars=s.max_hold_bars,cooldown_bars=s.cooldown_bars,risk_pct=s.risk_pct,max_leverage=s.max_leverage)

def setup_events(f:pd.DataFrame,s:P3Spec):
    return p2.setup_events(f,to_p2(s))

def run_backtest(f:pd.DataFrame,s:P3Spec,start=e.START,end=e.END,fee=.0004,slip=.0001,entry_delay_bars=0,extra_entry_penalty=0.):
    events=setup_events(f,s);by={}
    for ev in events: by.setdefault(ev['complete_i']+1+entry_delay_bars,[]).append(ev)
    O,H,L,C,A=(f[c].to_numpy(float) for c in ('open','high','low','close','atr'));idx=f.index;si=int(idx.searchsorted(start));ei=int(idx.searchsorted(end))
    equity=100000.;peak=equity;maxdd=0.;pos=None;cool=-1;rows=[]
    def finish(p,i,reason,px,qty=None):
        nonlocal equity,peak,maxdd,cool
        q=p['qty_open'] if qty is None else min(qty,p['qty_open'])
        gross=(px-p['entry'])*q if s.side=='LONG' else (p['entry']-px)*q
        fees=fee*(p['entry']+px)*q;net=gross-fees;equity+=net;p['realized_net']+=net;p['qty_open']-=q
        if p['qty_open']<=1e-12:
            exit_at_open=reason in ('TIME_DECAY','MAX_HOLD')
            total=p['realized_net'];rows.append({'architecture':s.architecture,'side':s.side,'signal_time':idx[p['signal_i']],'entry_time':idx[p['entry_i']],'exit_time':idx[i],'entry':p['entry'],'exit':px,'stop_initial':p['stop_initial'],'target_initial':p['target_initial'],'exit_reason':reason,'bars_held':i-p['entry_i'] if exit_at_open else i-p['entry_i']+1,'completed_bars_before_exit':i-p['entry_i'],'exit_at_bar_open':exit_at_open,'net_pnl':total,'fees':np.nan,'r_net':total/p['risk_cash_initial'],'equity_after':equity,'market_regime':p['market_regime'],'vol_regime':p['vol_regime'],'h4_trend_score':p['h4_trend_score'],'partial_taken':p['partial_taken'],'max_favourable_r':p['max_fav_r'],'protect_state':p['protect_state']})
            cool=i+s.cooldown_bars;peak=max(peak,equity);maxdd=min(maxdd,equity/peak-1)
    def update_favourable(p,i):
        if s.side=='LONG': p['best']=max(p['best'],H[i]);fav=p['best']-p['entry']
        else: p['best']=min(p['best'],L[i]);fav=p['entry']-p['best']
        p['max_fav_r']=max(p['max_fav_r'],fav/max(p['distance'],1e-12));return p['max_fav_r']
    def apply_protection_next_bar(p):
        if p['max_fav_r']>=s.breakeven_trigger_r:
            new=p['entry'];p['protect_state']='BREAKEVEN'
        elif p['max_fav_r']>=s.protect_trigger_r:
            new=p['entry']+s.protect_stop_r*p['distance'] if s.side=='LONG' else p['entry']-s.protect_stop_r*p['distance'];p['protect_state']='EARLY_PROTECT'
        else:return
        if s.side=='LONG':p['stop']=max(p['stop'],new)
        else:p['stop']=min(p['stop'],new)
    for i in range(max(1,si),min(ei,len(f))):
        if pos is not None:
            completed=i-pos['entry_i']
            # Decisions at current open use only excursion observed through previous bar.
            if completed>=s.time_decay_bars and pos['max_fav_r']<s.minimum_favourable_r:
                finish(pos,i,'TIME_DECAY',e.adverse(O[i],s.side,'EXIT',slip));pos=None;continue
            if completed>=s.max_hold_bars:
                finish(pos,i,'MAX_HOLD',e.adverse(O[i],s.side,'EXIT',slip));pos=None;continue
            stop_hit=L[i]<=pos['stop'] if s.side=='LONG' else H[i]>=pos['stop'];target_hit=H[i]>=pos['target'] if s.side=='LONG' else L[i]<=pos['target']
            if stop_hit or target_hit:
                reason='STOP' if stop_hit else 'TARGET';lvl=pos['stop'] if stop_hit else pos['target'];finish(pos,i,reason,e.trigger_fill(s.side,reason,O[i],lvl,slip));pos=None;continue
            partial_hit=(not pos['partial_taken']) and s.partial_fraction>0 and ((H[i]>=pos['partial_level']) if s.side=='LONG' else (L[i]<=pos['partial_level']))
            if partial_hit:
                finish(pos,i,'PARTIAL',e.trigger_fill(s.side,'TARGET',O[i],pos['partial_level'],slip),qty=pos['qty_initial']*s.partial_fraction)
                pos['partial_taken']=True;pos['protect_state']='PARTIAL_BE'
                pos['stop']=max(pos['stop'],pos['entry']) if s.side=='LONG' else min(pos['stop'],pos['entry'])
                if pos['qty_open']<=1e-12:pos=None;continue
            update_favourable(pos,i);apply_protection_next_bar(pos);continue
        if i<cool or i not in by:continue
        ev=max(by[i],key=lambda z:z['complete_i']);sig=ev['complete_i'];entry=e.adverse(O[i],s.side,'ENTRY',slip,extra_entry_penalty);st=float(ev['stop']);dist=entry-st if s.side=='LONG' else st-entry
        if not np.isfinite(dist) or dist<=0 or dist>A[sig]*s.atr_stop_cap*1.2:continue
        stop_exit=e.trigger_fill(s.side,'STOP',st,st,slip);risk_unit=(entry-stop_exit if s.side=='LONG' else stop_exit-entry)+fee*(entry+stop_exit);qty=min(equity*s.risk_pct/max(risk_unit,1e-12),equity*s.max_leverage/max(entry,1e-12))
        if qty<=0:continue
        tgt=entry+dist*s.reward_risk if s.side=='LONG' else entry-dist*s.reward_risk;partial=entry+dist*s.partial_r if s.side=='LONG' else entry-dist*s.partial_r;row=f.iloc[sig]
        pos={'signal_i':sig,'entry_i':i,'entry':entry,'qty_initial':qty,'qty_open':qty,'risk_cash_initial':risk_unit*qty,'distance':dist,'stop':st,'stop_initial':st,'target':tgt,'target_initial':tgt,'partial_level':partial,'partial_taken':False,'best':entry,'max_fav_r':0.,'protect_state':'INITIAL','realized_net':0.,'market_regime':str(row.market_regime),'vol_regime':str(row.vol_regime),'h4_trend_score':float(row.h4_trend_score)}
        stop_hit=L[i]<=st if s.side=='LONG' else H[i]>=st;target_hit=H[i]>=tgt if s.side=='LONG' else L[i]<=tgt
        if stop_hit or target_hit:
            reason='STOP' if stop_hit else 'TARGET';lvl=st if stop_hit else tgt;finish(pos,i,reason,e.trigger_fill(s.side,reason,O[i],lvl,slip));pos=None;continue
        partial_hit=s.partial_fraction>0 and ((H[i]>=partial) if s.side=='LONG' else (L[i]<=partial))
        if partial_hit:
            finish(pos,i,'PARTIAL',e.trigger_fill(s.side,'TARGET',O[i],partial,slip),qty=qty*s.partial_fraction);pos['partial_taken']=True;pos['protect_state']='PARTIAL_BE';pos['stop']=max(st,entry) if s.side=='LONG' else min(st,entry)
        update_favourable(pos,i);apply_protection_next_bar(pos)
    if pos is not None:
        fi=min(ei,len(f))-1;finish(pos,fi,'END_OF_WINDOW',e.adverse(C[fi],s.side,'EXIT',slip));pos=None
    t=pd.DataFrame(rows)
    if len(t):
        for c in ('signal_time','entry_time','exit_time'):t[c]=pd.to_datetime(t[c],utc=True)
    return t,e.metrics(t)

def main():
    ap=argparse.ArgumentParser();ap.add_argument('corpus',type=Path);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--protocol',type=Path,required=True);args=ap.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    ah=verify_lock(args.protocol/'ROUND23_PHASE3_ARCHITECTURES_LOCKED.json');ch=e.verify_lock(args.protocol/'ROUND23_CRITERIA_LOCKED.json');criteria=json.loads((args.protocol/'ROUND23_CRITERIA_LOCKED.json').read_text())
    raw=e.load_ohlcv(args.corpus);f=e.prepare_features(raw);reports=[]
    for central in central_specs():
      name=f'{central.architecture}__{central.side}';od=args.output/name;od.mkdir(exist_ok=True);variants=[];neigh=[]
      for j,s in enumerate(neighbours(central)):
        t,m=run_backtest(f,s);variants.append((s,t));basic=bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.10 and m['expectancy_r']>0);neigh.append({'variant':j,'spec':asdict(s),**m,'passes_basic':basic})
      ct=variants[0][1];month=e.month_table(ct);quarter=e.quarter_table(ct);regimes=e.regime_table(ct);years=e.year_table(ct)
      nt,nf=e.nested_monthly(variants);at,af=e.expanding_annual(variants);bt,bf=e.blocked_year_cv(variants)
      stresses={}
      for row in criteria['stress_matrix']:
        _,mm=run_backtest(f,central,fee=row['fee'],slip=row['slippage'],entry_delay_bars=row['entry_delay_bars'],extra_entry_penalty=row['extra_entry_penalty']);stresses[row['id']]=mm
      rr=ct.r_net.to_numpy(float) if len(ct) else np.array([]);iid=e.bootstrap_iid(rr);blocks=e.bootstrap_month(ct);mc=e.monte_carlo(ct);gate=e.gate_report(ct,month,quarter,nt,at,af,bt,bf,stresses,neigh,iid,blocks,mc,regimes,years,criteria)
      ct.to_csv(od/'central_trades.csv',index=False);month.to_csv(od/'central_months.csv',index=False);quarter.to_csv(od/'central_rolling_quarters.csv',index=False);regimes.to_csv(od/'central_regimes.csv',index=False);years.to_csv(od/'central_years.csv',index=False);nt.to_csv(od/'purged_monthly_outer_trades.csv',index=False);nf.to_json(od/'purged_monthly_folds.json',orient='records',indent=2);at.to_csv(od/'expanding_annual_outer_trades.csv',index=False);af.to_json(od/'expanding_annual_folds.json',orient='records',indent=2);bt.to_csv(od/'blocked_year_outer_trades.csv',index=False);bf.to_json(od/'blocked_year_folds.json',orient='records',indent=2);pd.DataFrame(neigh).to_json(od/'neighbours.json',orient='records',indent=2)
      report={'central_spec':asdict(central),'central':e.metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'stress':stresses,'iid_bootstrap':iid,'month_block_bootstrap':blocks,'monte_carlo':mc,'gate':gate};(od/'REPORT.json').write_text(json.dumps(e.json_safe(report),indent=2))
      reports.append({'architecture':central.architecture,'side':central.side,'passes_all':gate['passes_all'],'central':e.metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'failed_checks':gate['diagnostics']['failed_checks'],'report_path':str(od/'REPORT.json'),'minimum_annual_expectancy':float(af[af.outer_trades>0].outer_expectancy_r.min()) if (len(af) and (af.outer_trades>0).any()) else -math.inf,'mc_q95_dd':mc['q95_max_drawdown_abs']})
    dec={'phase':'ROUND23_PHASE3','stable':any(x['passes_all'] for x in reports),'admitted_count':sum(x['passes_all'] for x in reports),'round22_holdout_used':False,'scalp_engine_touched':False,'android_integration_allowed':False,'protocol_hashes':{'phase3_architectures':ah,'criteria':ch},'candidate_reports':reports};(args.output/'PHASE3_DECISION.json').write_text(json.dumps(e.json_safe(dec),indent=2));print(json.dumps(e.json_safe(dec),indent=2))
if __name__=='__main__':main()
