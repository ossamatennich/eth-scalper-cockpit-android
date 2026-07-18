#!/usr/bin/env python3
from __future__ import annotations
import argparse,hashlib,json,math,sys
from dataclasses import asdict,replace
from pathlib import Path
import numpy as np,pandas as pd
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import round23_engine as e
import round23_phase3 as p3

def verify(path):
 g=hashlib.sha256(path.read_bytes()).hexdigest();x=path.with_suffix('.sha256').read_text().split()[0]
 if g!=x:raise RuntimeError('lock mismatch')
 return g

def specs():
 out=[]
 for side in ('LONG','SHORT'):
  out += [
   p3.P3Spec('DRE_FAST_FAILURE',side,reward_risk=2.2,time_decay_bars=3,minimum_favourable_r=.15,protect_trigger_r=.6,protect_stop_r=-.25,breakeven_trigger_r=.9,max_hold_bars=16),
   p3.P3Spec('DRE_FAST_FAILURE_SCALEOUT',side,reward_risk=2.2,partial_r=.6,partial_fraction=.5,time_decay_bars=3,minimum_favourable_r=.15,max_hold_bars=16),
   p3.P3Spec('DRE_SHORT_HORIZON_ASYMMETRY',side,reward_risk=1.5,time_decay_bars=4,minimum_favourable_r=.2,protect_trigger_r=.5,protect_stop_r=-.2,breakeven_trigger_r=.75,max_hold_bars=10)
  ]
 return out

def neigh(s):
 out=[s]
 def add(k,vv):
  nonlocal out
  for v in vv:out.append(replace(s,**{k:v}))
 if s.architecture=='DRE_FAST_FAILURE':
  add('time_decay_bars',[2,4]);add('minimum_favourable_r',[.1,.25]);add('protect_trigger_r',[.5,.75]);add('protect_stop_r',[-.4,-.1]);add('reward_risk',[2.,2.4]);add('max_hold_bars',[12,20])
 elif s.architecture=='DRE_FAST_FAILURE_SCALEOUT':
  add('time_decay_bars',[2,4]);add('minimum_favourable_r',[.1,.25]);add('partial_r',[.5,.7]);add('partial_fraction',[.33,.67]);add('reward_risk',[2.,2.4]);add('max_hold_bars',[12,20])
 else:
  add('time_decay_bars',[3,6]);add('minimum_favourable_r',[.1,.3]);add('protect_trigger_r',[.4,.65]);add('protect_stop_r',[-.35,-.1]);add('reward_risk',[1.3,1.8]);add('max_hold_bars',[8,14])
 return list({json.dumps(asdict(x),sort_keys=True):x for x in out}.values())

def main():
 ap=argparse.ArgumentParser();ap.add_argument('corpus',type=Path);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--protocol',type=Path,required=True);a=ap.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 ah=verify(a.protocol/'ROUND23_PHASE5_ARCHITECTURES_LOCKED.json');ch=e.verify_lock(a.protocol/'ROUND23_CRITERIA_LOCKED.json');crit=json.loads((a.protocol/'ROUND23_CRITERIA_LOCKED.json').read_text());f=e.prepare_features(e.load_ohlcv(a.corpus));reports=[]
 for c in specs():
  od=a.output/f'{c.architecture}__{c.side}';od.mkdir(exist_ok=True);variants=[];nr=[]
  for j,s in enumerate(neigh(c)):
   t,m=p3.run_backtest(f,s);variants.append((s,t));nr.append({'variant':j,'spec':asdict(s),**m,'passes_basic':bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.1 and m['expectancy_r']>0)})
  ct=variants[0][1];mo=e.month_table(ct);qu=e.quarter_table(ct);rg=e.regime_table(ct);yr=e.year_table(ct);nt,nf=e.nested_monthly(variants);at,af=e.expanding_annual(variants);bt,bf=e.blocked_year_cv(variants);stress={}
  for r in crit['stress_matrix']:
   _,mm=p3.run_backtest(f,c,fee=r['fee'],slip=r['slippage'],entry_delay_bars=r['entry_delay_bars'],extra_entry_penalty=r['extra_entry_penalty']);stress[r['id']]=mm
  rr=ct.r_net.to_numpy(float) if len(ct) else np.array([]);iid=e.bootstrap_iid(rr);bl=e.bootstrap_month(ct);mc=e.monte_carlo(rr);gate=e.gate_report(ct,mo,qu,nt,at,af,bt,bf,stress,nr,iid,bl,mc,rg,yr,crit)
  ct.to_csv(od/'central_trades.csv',index=False);mo.to_csv(od/'central_months.csv',index=False);qu.to_csv(od/'central_rolling_quarters.csv',index=False);rg.to_csv(od/'central_regimes.csv',index=False);yr.to_csv(od/'central_years.csv',index=False);nt.to_csv(od/'purged_monthly_outer_trades.csv',index=False);nf.to_json(od/'purged_monthly_folds.json',orient='records',indent=2);at.to_csv(od/'expanding_annual_outer_trades.csv',index=False);af.to_json(od/'expanding_annual_folds.json',orient='records',indent=2);bt.to_csv(od/'blocked_year_outer_trades.csv',index=False);bf.to_json(od/'blocked_year_folds.json',orient='records',indent=2);pd.DataFrame(nr).to_json(od/'neighbours.json',orient='records',indent=2)
  rep={'central_spec':asdict(c),'central':e.metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'stress':stress,'iid_bootstrap':iid,'month_block_bootstrap':bl,'monte_carlo':mc,'gate':gate};(od/'REPORT.json').write_text(json.dumps(e.json_safe(rep),indent=2));reports.append({'architecture':c.architecture,'side':c.side,'passes_all':gate['passes_all'],'central':e.metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'failed_checks':gate['diagnostics']['failed_checks'],'report_path':str(od/'REPORT.json'),'minimum_annual_expectancy':float(af[af.outer_trades>0].outer_expectancy_r.min()) if len(af) and (af.outer_trades>0).any() else -math.inf,'mc_q95_dd':mc['q95_max_drawdown_abs']})
 d={'phase':'ROUND23_PHASE5','stable':any(x['passes_all'] for x in reports),'admitted_count':sum(x['passes_all'] for x in reports),'round22_holdout_used':False,'scalp_engine_touched':False,'android_integration_allowed':False,'protocol_hashes':{'phase5_architectures':ah,'criteria':ch},'candidate_reports':reports};(a.output/'PHASE5_DECISION.json').write_text(json.dumps(e.json_safe(d),indent=2));print(json.dumps(e.json_safe(d),indent=2))
if __name__=='__main__':main()
