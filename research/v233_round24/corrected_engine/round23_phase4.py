#!/usr/bin/env python3
from __future__ import annotations
import argparse, hashlib, json, math, sys
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal
import numpy as np
import pandas as pd
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import round23_engine as e
Side=Literal['LONG','SHORT']

@dataclass(frozen=True)
class P4Spec:
    architecture:str;side:Side
    regime_threshold:float=.15;h4_adx_min:float=16.;channel_bars:int=12;range_atr_min:float=1.1;body_atr_min:float=.4
    confirm_body_atr_min:float=.1;retest_bars:int=4;retest_tolerance_atr:float=.25;pullback_lookback:int=3
    close_location_min:float=.62;rel_volume_min:float=.65;atr_stop_mult:float=1.3;atr_stop_cap:float=3.
    reward_risk:float=2.;max_hold_bars:int=16;breakeven_r:float=.8;trail_start_r:float=1.3;trail_atr_mult:float=1.5
    cooldown_bars:int=4;risk_pct:float=.0015;max_leverage:float=1.

def verify_lock(path):
    got=hashlib.sha256(path.read_bytes()).hexdigest();exp=path.with_suffix('.sha256').read_text().split()[0]
    if got!=exp:raise RuntimeError('lock mismatch')
    return got

def central_specs():
 out=[]
 for side in ('LONG','SHORT'):
  out += [
   P4Spec('BREAKOUT_FOLLOW_THROUGH_CONFIRMATION',side,regime_threshold=.15,h4_adx_min=16,channel_bars=12,range_atr_min=1.1,body_atr_min=.4,confirm_body_atr_min=.1,rel_volume_min=.9,atr_stop_mult=1.3,reward_risk=2,max_hold_bars=16,breakeven_r=.8,trail_start_r=1.3,trail_atr_mult=1.5),
   P4Spec('BREAKOUT_RETEST_CONTINUATION',side,regime_threshold=.15,h4_adx_min=16,channel_bars=12,range_atr_min=1.1,body_atr_min=.4,retest_bars=4,retest_tolerance_atr=.25,confirm_body_atr_min=.05,atr_stop_mult=1.25,reward_risk=2,max_hold_bars=18,breakeven_r=.8,trail_start_r=1.4,trail_atr_mult=1.6),
   P4Spec('TREND_EMA_REJECTION',side,regime_threshold=.2,h4_adx_min=16,pullback_lookback=3,close_location_min=.62,body_atr_min=.15,rel_volume_min=.65,atr_stop_mult=1.25,reward_risk=1.6,max_hold_bars=12,breakeven_r=.65,trail_start_r=1.1,trail_atr_mult=1.4),
   P4Spec('CHANDELIER_TIME_SERIES_MOMENTUM',side,regime_threshold=.2,h4_adx_min=15,channel_bars=8,close_location_min=.6,rel_volume_min=.65,atr_stop_mult=1.4,reward_risk=8,max_hold_bars=30,breakeven_r=.8,trail_start_r=.9,trail_atr_mult=1.35)
  ]
 return out

def neighbours(s):
 out=[s]
 def add(k,vals):
  nonlocal out
  for v in vals:out.append(replace(s,**{k:v}))
 if s.architecture=='BREAKOUT_FOLLOW_THROUGH_CONFIRMATION':
  add('channel_bars',[8,20]);add('range_atr_min',[.95,1.25]);add('confirm_body_atr_min',[0,.2]);add('reward_risk',[1.8,2.2]);add('breakeven_r',[.6,1.]);add('max_hold_bars',[12,20])
 elif s.architecture=='BREAKOUT_RETEST_CONTINUATION':
  add('channel_bars',[8,20]);add('retest_bars',[2,6]);add('retest_tolerance_atr',[.15,.4]);add('reward_risk',[1.8,2.2]);add('breakeven_r',[.6,1.]);add('max_hold_bars',[14,22])
 elif s.architecture=='TREND_EMA_REJECTION':
  add('regime_threshold',[.15,.3]);add('pullback_lookback',[2,5]);add('body_atr_min',[.05,.25]);add('rel_volume_min',[.5,.8]);add('reward_risk',[1.4,1.8]);add('max_hold_bars',[9,16])
 elif s.architecture=='CHANDELIER_TIME_SERIES_MOMENTUM':
  add('regime_threshold',[.1,.3]);add('channel_bars',[6,12]);add('h4_adx_min',[12,18]);add('breakeven_r',[.6,1.]);add('trail_atr_mult',[1.1,1.7]);add('max_hold_bars',[20,40])
 return list({json.dumps(asdict(x),sort_keys=True):x for x in out}.values())

def stop_at(f,i,s,swing_n):
 c=float(f.close.iloc[i]);a=float(f.atr.iloc[i])
 if s.side=='LONG':st=min(float(f[f'swing_low_{swing_n}'].iloc[i]),c-s.atr_stop_mult*a);dist=c-st
 else:st=max(float(f[f'swing_high_{swing_n}'].iloc[i]),c+s.atr_stop_mult*a);dist=st-c
 return st,dist,a

def setup_events(f,s):
 n=len(f);C=f.close.to_numpy(float);O=f.open.to_numpy(float);H=f.high.to_numpy(float);L=f.low.to_numpy(float);A=f.atr.to_numpy(float);rv=f.rel_volume.to_numpy(float)
 score=pd.to_numeric(f.h4_trend_score,errors='coerce').fillna(0).to_numpy(float);adx=pd.to_numeric(f.h4_adx,errors='coerce').fillna(np.nan).to_numpy(float)
 common=np.isfinite(A)&np.isfinite(rv)&(f.atr_pct.to_numpy(float)>=.0025)&(f.atr_pct.to_numpy(float)<=.05);dir_ok=score>=s.regime_threshold if s.side=='LONG' else score<=-s.regime_threshold
 events=[]
 if s.architecture in ('BREAKOUT_FOLLOW_THROUGH_CONFIRMATION','BREAKOUT_RETEST_CONTINUATION'):
  if s.side=='LONG':br=C>f[f'prior_high_{s.channel_bars}'].to_numpy(float);clv=f.clv_long.to_numpy(float);body=f.body_atr.to_numpy(float)>=s.body_atr_min;level=f[f'prior_high_{s.channel_bars}'].to_numpy(float)
  else:br=C<f[f'prior_low_{s.channel_bars}'].to_numpy(float);clv=f.clv_short.to_numpy(float);body=f.body_atr.to_numpy(float)<=-s.body_atr_min;level=f[f'prior_low_{s.channel_bars}'].to_numpy(float)
  # F04: close_location_min is active for follow-through and retest families.
  base=common&dir_ok&(adx>=s.h4_adx_min)&br&(f.range_atr.to_numpy(float)>=s.range_atr_min)&body&(clv>=s.close_location_min)&(rv>=s.rel_volume_min);base[:120]=False
  for i in np.flatnonzero(base):
   comp=None
   if s.architecture=='BREAKOUT_FOLLOW_THROUGH_CONFIRMATION':
    j=i+1
    if j<n:
     ok=(C[j]>C[i] and f.body_atr.iloc[j]>=s.confirm_body_atr_min and L[j]>=level[i]-.35*A[i]) if s.side=='LONG' else (C[j]<C[i] and f.body_atr.iloc[j]<=-s.confirm_body_atr_min and H[j]<=level[i]+.35*A[i])
     if ok:comp=j
   else:
    for j in range(i+1,min(n,i+1+s.retest_bars)):
     ok=(L[j]<=level[i]+s.retest_tolerance_atr*A[i] and C[j]>level[i] and f.body_atr.iloc[j]>=s.confirm_body_atr_min) if s.side=='LONG' else (H[j]>=level[i]-s.retest_tolerance_atr*A[i] and C[j]<level[i] and f.body_atr.iloc[j]<=-s.confirm_body_atr_min)
     if ok:comp=j;break
   if comp is not None:
    st,dist,a=stop_at(f,comp,s,8)
    if np.isfinite(st) and s.atr_stop_mult*a<=dist<=s.atr_stop_cap*a:events.append({'signal_i':int(i),'complete_i':int(comp),'stop':float(st)})
 elif s.architecture=='TREND_EMA_REJECTION':
  ema20=f.ema20.to_numpy(float);ema50=f.ema50.to_numpy(float);aligned=ema20>ema50 if s.side=='LONG' else ema20<ema50
  touched=np.zeros(n,bool)
  for k in range(0,s.pullback_lookback):
   if s.side=='LONG':touched|=np.roll(L<=ema20+.1*A,k)
   else:touched|=np.roll(H>=ema20-.1*A,k)
  touched[:s.pullback_lookback+2]=False
  if s.side=='LONG':rej=(C>ema20)&(f.body_atr.to_numpy(float)>=s.body_atr_min)&(f.clv_long.to_numpy(float)>=s.close_location_min)
  else:rej=(C<ema20)&(f.body_atr.to_numpy(float)<=-s.body_atr_min)&(f.clv_short.to_numpy(float)>=s.close_location_min)
  valid=common&dir_ok&(adx>=s.h4_adx_min)&aligned&touched&rej&(rv>=s.rel_volume_min);valid[:120]=False
  for i in np.flatnonzero(valid):
   st,dist,a=stop_at(f,i,s,8)
   if np.isfinite(st) and s.atr_stop_mult*a<=dist<=s.atr_stop_cap*a:events.append({'signal_i':int(i),'complete_i':int(i),'stop':float(st)})
 elif s.architecture=='CHANDELIER_TIME_SERIES_MOMENTUM':
  ema20=f.ema20.to_numpy(float);ema50=f.ema50.to_numpy(float);aligned=ema20>ema50 if s.side=='LONG' else ema20<ema50
  if s.side=='LONG':br=C>f[f'prior_high_{s.channel_bars}'].to_numpy(float);clv=f.clv_long.to_numpy(float)
  else:br=C<f[f'prior_low_{s.channel_bars}'].to_numpy(float);clv=f.clv_short.to_numpy(float)
  valid=common&dir_ok&(adx>=s.h4_adx_min)&aligned&br&(clv>=s.close_location_min)&(rv>=s.rel_volume_min);valid[:120]=False
  for i in np.flatnonzero(valid):
   st,dist,a=stop_at(f,i,s,12)
   if np.isfinite(st) and s.atr_stop_mult*a<=dist<=s.atr_stop_cap*a:events.append({'signal_i':int(i),'complete_i':int(i),'stop':float(st)})
 else:raise ValueError(s.architecture)
 return events

def run_backtest(f,s,start=e.START,end=e.END,fee=.0004,slip=.0001,entry_delay_bars=0,extra_entry_penalty=0.):
 events=setup_events(f,s);by={}
 for ev in events:by.setdefault(ev['complete_i']+1+entry_delay_bars,[]).append(ev)
 O,H,L,C,A=(f[c].to_numpy(float) for c in ('open','high','low','close','atr'));idx=f.index;si=int(idx.searchsorted(start));ei=int(idx.searchsorted(end));equity=100000.;pos=None;cool=-1;rows=[]
 def close(p,i,reason,px):
  nonlocal equity,cool
  gross=(px-p['entry'])*p['qty'] if s.side=='LONG' else (p['entry']-px)*p['qty'];fees=fee*(p['entry']+px)*p['qty'];net=gross-fees;equity+=net
  exit_at_open=reason=='MAX_HOLD'
  rows.append({'architecture':s.architecture,'side':s.side,'signal_time':idx[p['signal_i']],'entry_time':idx[p['entry_i']],'exit_time':idx[i],'entry':p['entry'],'exit':px,'stop_initial':p['stop_initial'],'target_initial':p['target_initial'],'exit_reason':reason,'bars_held':i-p['entry_i'] if exit_at_open else i-p['entry_i']+1,'completed_bars_before_exit':i-p['entry_i'],'exit_at_bar_open':exit_at_open,'net_pnl':net,'fees':fees,'r_net':net/(p['risk_unit']*p['qty']),'equity_after':equity,'market_regime':p['market_regime'],'vol_regime':p['vol_regime'],'h4_trend_score':p['h4_trend_score']});cool=i+s.cooldown_bars
 def trail(p,i):
  if s.side=='LONG':p['best']=max(p['best'],H[i]);fav=p['best']-p['entry']
  else:p['best']=min(p['best'],L[i]);fav=p['entry']-p['best']
  r=fav/max(p['distance'],1e-12)
  if r>=s.breakeven_r:p['stop']=max(p['stop'],p['entry']) if s.side=='LONG' else min(p['stop'],p['entry'])
  if r>=s.trail_start_r:
   tr=p['best']-A[i]*s.trail_atr_mult if s.side=='LONG' else p['best']+A[i]*s.trail_atr_mult;p['stop']=max(p['stop'],tr) if s.side=='LONG' else min(p['stop'],tr)
 for i in range(max(1,si),min(ei,len(f))):
  if pos is not None:
   completed=i-pos['entry_i']
   if completed>=s.max_hold_bars:close(pos,i,'MAX_HOLD',e.adverse(O[i],s.side,'EXIT',slip));pos=None;continue
   sh=L[i]<=pos['stop'] if s.side=='LONG' else H[i]>=pos['stop'];th=H[i]>=pos['target'] if s.side=='LONG' else L[i]<=pos['target']
   if sh or th:
    reason='STOP' if sh else 'TARGET';lvl=pos['stop'] if sh else pos['target'];close(pos,i,reason,e.trigger_fill(s.side,reason,O[i],lvl,slip));pos=None;continue
   trail(pos,i);continue
  if i<cool or i not in by:continue
  ev=max(by[i],key=lambda z:z['complete_i']);sig=ev['complete_i'];entry=e.adverse(O[i],s.side,'ENTRY',slip,extra_entry_penalty);st=float(ev['stop']);dist=entry-st if s.side=='LONG' else st-entry
  if not np.isfinite(dist) or dist<=0 or dist>A[sig]*s.atr_stop_cap*1.2:continue
  stop_exit=e.trigger_fill(s.side,'STOP',st,st,slip);risk_unit=(entry-stop_exit if s.side=='LONG' else stop_exit-entry)+fee*(entry+stop_exit);qty=min(equity*s.risk_pct/max(risk_unit,1e-12),equity*s.max_leverage/max(entry,1e-12))
  if qty<=0:continue
  tgt=entry+dist*s.reward_risk if s.side=='LONG' else entry-dist*s.reward_risk;row=f.iloc[sig];pos={'signal_i':ev['signal_i'],'entry_i':i,'entry':entry,'qty':qty,'risk_unit':risk_unit,'distance':dist,'stop':st,'stop_initial':st,'target':tgt,'target_initial':tgt,'best':entry,'market_regime':str(row.market_regime),'vol_regime':str(row.vol_regime),'h4_trend_score':float(row.h4_trend_score)}
  sh=L[i]<=st if s.side=='LONG' else H[i]>=st;th=H[i]>=tgt if s.side=='LONG' else L[i]<=tgt
  if sh or th:
   reason='STOP' if sh else 'TARGET';lvl=st if sh else tgt;close(pos,i,reason,e.trigger_fill(s.side,reason,O[i],lvl,slip));pos=None
  else:trail(pos,i)
 if pos is not None:
  fi=min(ei,len(f))-1;close(pos,fi,'END_OF_WINDOW',e.adverse(C[fi],s.side,'EXIT',slip))
 t=pd.DataFrame(rows)
 if len(t):
  for c in ('signal_time','entry_time','exit_time'):t[c]=pd.to_datetime(t[c],utc=True)
 return t,e.metrics(t)

def main():
 ap=argparse.ArgumentParser();ap.add_argument('corpus',type=Path);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--protocol',type=Path,required=True);args=ap.parse_args();args.output.mkdir(parents=True,exist_ok=True)
 ah=verify_lock(args.protocol/'ROUND23_PHASE4_ARCHITECTURES_LOCKED.json');ch=e.verify_lock(args.protocol/'ROUND23_CRITERIA_LOCKED.json');criteria=json.loads((args.protocol/'ROUND23_CRITERIA_LOCKED.json').read_text());f=e.prepare_features(e.load_ohlcv(args.corpus));reports=[]
 for central in central_specs():
  name=f'{central.architecture}__{central.side}';od=args.output/name;od.mkdir(exist_ok=True);variants=[];neigh=[]
  for j,s in enumerate(neighbours(central)):
   t,m=run_backtest(f,s);variants.append((s,t));neigh.append({'variant':j,'spec':asdict(s),**m,'passes_basic':bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.10 and m['expectancy_r']>0)})
  ct=variants[0][1];month=e.month_table(ct);quarter=e.quarter_table(ct);reg=e.regime_table(ct);yrs=e.year_table(ct);nt,nf=e.nested_monthly(variants);at,af=e.expanding_annual(variants);bt,bf=e.blocked_year_cv(variants);stresses={}
  for row in criteria['stress_matrix']:
   _,mm=run_backtest(f,central,fee=row['fee'],slip=row['slippage'],entry_delay_bars=row['entry_delay_bars'],extra_entry_penalty=row['extra_entry_penalty']);stresses[row['id']]=mm
  rr=ct.r_net.to_numpy(float) if len(ct) else np.array([]);iid=e.bootstrap_iid(rr);blocks=e.bootstrap_month(ct);mc=e.monte_carlo(ct);gate=e.gate_report(ct,month,quarter,nt,at,af,bt,bf,stresses,neigh,iid,blocks,mc,reg,yrs,criteria)
  ct.to_csv(od/'central_trades.csv',index=False);month.to_csv(od/'central_months.csv',index=False);quarter.to_csv(od/'central_rolling_quarters.csv',index=False);reg.to_csv(od/'central_regimes.csv',index=False);yrs.to_csv(od/'central_years.csv',index=False);nt.to_csv(od/'purged_monthly_outer_trades.csv',index=False);nf.to_json(od/'purged_monthly_folds.json',orient='records',indent=2);at.to_csv(od/'expanding_annual_outer_trades.csv',index=False);af.to_json(od/'expanding_annual_folds.json',orient='records',indent=2);bt.to_csv(od/'blocked_year_outer_trades.csv',index=False);bf.to_json(od/'blocked_year_folds.json',orient='records',indent=2);pd.DataFrame(neigh).to_json(od/'neighbours.json',orient='records',indent=2)
  rep={'central_spec':asdict(central),'central':e.metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'stress':stresses,'iid_bootstrap':iid,'month_block_bootstrap':blocks,'monte_carlo':mc,'gate':gate};(od/'REPORT.json').write_text(json.dumps(e.json_safe(rep),indent=2));reports.append({'architecture':central.architecture,'side':central.side,'passes_all':gate['passes_all'],'central':e.metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'failed_checks':gate['diagnostics']['failed_checks'],'report_path':str(od/'REPORT.json'),'minimum_annual_expectancy':float(af[af.outer_trades>0].outer_expectancy_r.min()) if len(af) and (af.outer_trades>0).any() else -math.inf,'mc_q95_dd':mc['q95_max_drawdown_abs']})
 dec={'phase':'ROUND23_PHASE4','stable':any(x['passes_all'] for x in reports),'admitted_count':sum(x['passes_all'] for x in reports),'round22_holdout_used':False,'scalp_engine_touched':False,'android_integration_allowed':False,'protocol_hashes':{'phase4_architectures':ah,'criteria':ch},'candidate_reports':reports};(args.output/'PHASE4_DECISION.json').write_text(json.dumps(e.json_safe(dec),indent=2));print(json.dumps(e.json_safe(dec),indent=2))
if __name__=='__main__':main()
