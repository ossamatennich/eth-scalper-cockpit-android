#!/usr/bin/env python3
from __future__ import annotations
import argparse,hashlib,json,math,sys,heapq
from dataclasses import dataclass,asdict,replace
from pathlib import Path
import numpy as np,pandas as pd
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import round23_engine as e
import round23_phase6 as p6

@dataclass(frozen=True)
class GovSpec:
 architecture:str
 rolling_closed_trades:int=99
 loss_sum_trigger_r:float=-99.
 pause_hours:int=0
 consecutive_losses:int=99
 portfolio_rolling_closed_trades:int=99
 portfolio_loss_sum_trigger_r:float=-99.
 portfolio_pause_hours:int=0
 sleeve_consecutive_losses:int=99
 sleeve_pause_hours:int=0
 variant_id:str='CENTRAL'

def verify(p):
 g=hashlib.sha256(p.read_bytes()).hexdigest();x=p.with_suffix('.sha256').read_text().split()[0]
 if g!=x:raise RuntimeError('lock mismatch')
 return g

def central_specs():return [GovSpec('PORTFOLIO_LOSS_CLUSTER_COOLDOWN',rolling_closed_trades=4,loss_sum_trigger_r=-1.25,pause_hours=72),GovSpec('SLEEVE_CONSECUTIVE_LOSS_COOLDOWN',consecutive_losses=2,pause_hours=48),GovSpec('DUAL_RISK_GOVERNOR',portfolio_rolling_closed_trades=5,portfolio_loss_sum_trigger_r=-1.5,portfolio_pause_hours=48,sleeve_consecutive_losses=2,sleeve_pause_hours=48)]
def neighbours(s):
 out=[s]
 def add(k,vals):
  nonlocal out
  for v in vals:out.append(replace(s,variant_id=f'{k}_{v}',**{k:v}))
 if s.architecture=='PORTFOLIO_LOSS_CLUSTER_COOLDOWN':add('rolling_closed_trades',[3,6]);add('loss_sum_trigger_r',[-1.,-1.75]);add('pause_hours',[48,96])
 elif s.architecture=='SLEEVE_CONSECUTIVE_LOSS_COOLDOWN':add('consecutive_losses',[1,3]);add('pause_hours',[24,72])
 else:add('portfolio_rolling_closed_trades',[3,7]);add('portfolio_loss_sum_trigger_r',[-1.,-2.]);add('portfolio_pause_hours',[24,72]);add('sleeve_consecutive_losses',[1,3]);add('sleeve_pause_hours',[24,72])
 return list({json.dumps(asdict(x),sort_keys=True):x for x in out}.values())

def overlay(t,s):
 if not len(t):return t.copy()
 t=t.sort_values(['entry_time','exit_time','module']).reset_index(drop=True);accepted=[];pending=[];seq=0;portfolio_hist=[];sleeve_hist={};portfolio_pause=pd.Timestamp.min.tz_localize('UTC');sleeve_pause={}
 def process(until):
  nonlocal portfolio_pause
  while pending and pending[0][0]<until:
   _,_,r=heapq.heappop(pending);rv=float(r['r_net']);mod=str(r['module']);xt=pd.Timestamp(r['exit_time'])
   portfolio_hist.append(rv);sleeve_hist.setdefault(mod,[]).append(rv)
   if s.architecture=='PORTFOLIO_LOSS_CLUSTER_COOLDOWN' and len(portfolio_hist)>=s.rolling_closed_trades and sum(portfolio_hist[-s.rolling_closed_trades:])<=s.loss_sum_trigger_r:portfolio_pause=max(portfolio_pause,xt+pd.Timedelta(hours=s.pause_hours))
   elif s.architecture=='SLEEVE_CONSECUTIVE_LOSS_COOLDOWN':
    h=sleeve_hist[mod];n=s.consecutive_losses
    if len(h)>=n and all(x<0 for x in h[-n:]):sleeve_pause[mod]=max(sleeve_pause.get(mod,pd.Timestamp.min.tz_localize('UTC')),xt+pd.Timedelta(hours=s.pause_hours))
   else:
    if len(portfolio_hist)>=s.portfolio_rolling_closed_trades and sum(portfolio_hist[-s.portfolio_rolling_closed_trades:])<=s.portfolio_loss_sum_trigger_r:portfolio_pause=max(portfolio_pause,xt+pd.Timedelta(hours=s.portfolio_pause_hours))
    h=sleeve_hist[mod];n=s.sleeve_consecutive_losses
    if len(h)>=n and all(x<0 for x in h[-n:]):sleeve_pause[mod]=max(sleeve_pause.get(mod,pd.Timestamp.min.tz_localize('UTC')),xt+pd.Timedelta(hours=s.sleeve_pause_hours))
 for _,r in t.iterrows():
  et=pd.Timestamp(r.entry_time);process(et);mod=str(r.module)
  if et<portfolio_pause or et<sleeve_pause.get(mod,pd.Timestamp.min.tz_localize('UTC')):continue
  accepted.append(r);heapq.heappush(pending,(pd.Timestamp(r.exit_time),seq,r));seq+=1
 return pd.DataFrame(accepted).reset_index(drop=True) if accepted else t.iloc[0:0].copy()

def side_metrics(t):return {q:e.metrics(t[t.side==q]) for q in ('LONG','SHORT')}|{'COMBINED':e.metrics(t)}
def base_spec():return p6.EnsembleSpec('ASYMMETRIC_MULTI_PATTERN_ENSEMBLE')
def governed(f,s,fee=.0004,slip=.0001,entry_delay_bars=0,extra_entry_penalty=0.):return overlay(p6.module_trades(f,base_spec(),fee,slip,entry_delay_bars,extra_entry_penalty),s)

def main():
 ap=argparse.ArgumentParser();ap.add_argument('corpus',type=Path);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--protocol',type=Path,required=True);a=ap.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 ah=verify(a.protocol/'ROUND23_PHASE7_ARCHITECTURES_LOCKED.json');ch=e.verify_lock(a.protocol/'ROUND23_CRITERIA_LOCKED.json')
 crit=json.loads((a.protocol/'ROUND23_CRITERIA_LOCKED.json').read_text());f=e.prepare_features(e.load_ohlcv(a.corpus));reports=[]
 for c in central_specs():
  od=a.output/c.architecture;od.mkdir(exist_ok=True);variants=[];nr=[]
  for j,s in enumerate(neighbours(c)):
   t=governed(f,s);m=e.metrics(t);variants.append((s,t));nr.append({'variant':j,'spec':asdict(s),**m,'passes_basic':bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.1 and m['expectancy_r']>0)})
  ct=variants[0][1];mo=e.month_table(ct);qu=e.quarter_table(ct);rg=e.regime_table(ct);yr=e.year_table(ct)
  nt,nf=e.nested_monthly(variants);at,af=e.expanding_annual(variants);bt,bf=e.blocked_year_cv(variants)
  stress_trades={r['id']:governed(f,c,r['fee'],r['slippage'],r['entry_delay_bars'],r['extra_entry_penalty']) for r in crit['stress_matrix']}
  gates={key:e.scoped_gate_report(ct,nt,at,bt,stress_trades,variants,crit,side) for key,side in (('LONG','LONG'),('SHORT','SHORT'),('COMBINED',None))}
  passes_all=all(x['passes_all'] for x in gates.values());mc=e.monte_carlo(ct)
  ct.to_csv(od/'central_trades.csv',index=False);mo.to_csv(od/'central_months.csv',index=False);qu.to_csv(od/'central_rolling_quarters.csv',index=False);rg.to_csv(od/'central_regimes.csv',index=False);yr.to_csv(od/'central_years.csv',index=False);nt.to_csv(od/'purged_monthly_outer_trades.csv',index=False);nf.to_json(od/'purged_monthly_folds.json',orient='records',indent=2);at.to_csv(od/'expanding_annual_outer_trades.csv',index=False);af.to_json(od/'expanding_annual_folds.json',orient='records',indent=2);bt.to_csv(od/'blocked_year_outer_trades.csv',index=False);bf.to_json(od/'blocked_year_folds.json',orient='records',indent=2);pd.DataFrame(nr).to_json(od/'neighbours.json',orient='records',indent=2)
  rep={'central_spec':asdict(c),'portfolio_rules':p6.PORTFOLIO_RULES,'central':e.metrics(ct),'direction_metrics':side_metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'stress':{k:e.metrics(v) for k,v in stress_trades.items()},'temporal_block_bootstrap':e.bootstrap_month(ct),'temporal_cluster_monte_carlo':mc,'gates_long_short_combined':gates}
  (od/'REPORT.json').write_text(json.dumps(e.json_safe(rep),indent=2))
  reports.append({'architecture':c.architecture,'side':'LONG_SHORT_COMBINED_LOCKED','passes_all':passes_all,'central':e.metrics(ct),'direction_metrics':side_metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'failed_checks':{k:v['diagnostics']['failed_checks'] for k,v in gates.items()},'report_path':str(od/'REPORT.json'),'minimum_annual_expectancy':float(af[af.outer_trades>0].outer_expectancy_r.min()) if len(af) and (af.outer_trades>0).any() else -math.inf,'mc_q95_dd':mc['q95_max_drawdown_abs']})
 d={'phase':'ROUND23_PHASE7_CORRECTED','stable':any(x['passes_all'] for x in reports),'admitted_count':sum(x['passes_all'] for x in reports),'round22_holdout_used':False,'scalp_engine_touched':False,'android_integration_allowed':False,'portfolio_rules':p6.PORTFOLIO_RULES,'protocol_hashes':{'phase7_architectures':ah,'criteria':ch},'candidate_reports':reports}
 (a.output/'PHASE7_DECISION.json').write_text(json.dumps(e.json_safe(d),indent=2));print(json.dumps(e.json_safe(d),indent=2))
if __name__=='__main__':main()
