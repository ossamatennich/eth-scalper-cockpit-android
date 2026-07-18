#!/usr/bin/env python3
from __future__ import annotations
import argparse,hashlib,json,math,sys
from dataclasses import asdict,replace,dataclass
from pathlib import Path
import numpy as np,pandas as pd
HERE=Path(__file__).resolve().parent;sys.path.insert(0,str(HERE))
import round23_engine as e
import round23_phase2 as p2
import round23_phase4 as p4

# F02 locked before corrected results: deterministic collision priority, at
# most one active sleeve per direction, 0.30% total nominal risk and 2.0x
# aggregate nominal leverage.  Each sleeve is conservatively charged its
# configured 0.15% risk and 1.0x maximum leverage.
PORTFOLIO_RULES={
 'rule_id':'ROUND23_CORRECTION_PORTFOLIO_V1',
 'module_priority':['DRE_SHORT','VCE_SHORT','FOLLOW_SHORT','MOMENTUM_LONG','VCE_LONG'],
 'same_direction_overlap':'REJECT_NEW',
 'same_timestamp_collision':'FIXED_MODULE_PRIORITY',
 'opposite_direction_overlap':'ALLOW_WITHIN_CAPS',
 'risk_per_sleeve':.0015,
 'max_total_risk':.0030,
 'leverage_per_sleeve_upper_bound':1.0,
 'max_total_leverage':2.0,
}

@dataclass(frozen=True)
class EnsembleSpec:
 architecture:str
 variant_id:str='CENTRAL'
 vce_long_rel_volume:float=1.10
 vce_short_rel_volume:float=1.10
 dre_short_body:float=.5
 dre_short_range:float=1.2
 follow_confirm_body:float=.1
 momentum_long_threshold:float=1.4

def verify(path):
 g=hashlib.sha256(path.read_bytes()).hexdigest();x=path.with_suffix('.sha256').read_text().split()[0]
 if g!=x:raise RuntimeError('lock mismatch')
 return g

def specs():
 return [EnsembleSpec('ASYMMETRIC_MULTI_PATTERN_ENSEMBLE'),EnsembleSpec('TWO_SLEEVE_MOMENTUM_ENSEMBLE')]

def neighbours(s):
 out=[s]
 if s.architecture=='ASYMMETRIC_MULTI_PATTERN_ENSEMBLE':
  for v in (.95,1.25):out.append(replace(s,variant_id=f'VCE_LONG_RV_{v}',vce_long_rel_volume=v))
  for v in (.95,1.25):out.append(replace(s,variant_id=f'VCE_SHORT_RV_{v}',vce_short_rel_volume=v))
  for v in (.35,.65):out.append(replace(s,variant_id=f'DRE_BODY_{v}',dre_short_body=v))
  for v in (0.,.2):out.append(replace(s,variant_id=f'FOLLOW_BODY_{v}',follow_confirm_body=v))
 else:
  for v in (1.2,1.6):out.append(replace(s,variant_id=f'MOM_THRESHOLD_{v}',momentum_long_threshold=v))
  for v in (.35,.65):out.append(replace(s,variant_id=f'DRE_BODY_{v}',dre_short_body=v))
  for v in (1.05,1.35):out.append(replace(s,variant_id=f'DRE_RANGE_{v}',dre_short_range=v))
 return out

def apply_portfolio_rules(t):
 if not len(t):return t.copy()
 priority={name:i for i,name in enumerate(PORTFOLIO_RULES['module_priority'])}
 z=t.copy();z['_priority']=z.module.map(priority).fillna(len(priority)).astype(int)
 z=z.sort_values(['entry_time','_priority','module','exit_time']).reset_index(drop=True)
 accepted=[];active=[]
 for et,g in z.groupby('entry_time',sort=True):
  et=pd.Timestamp(et);active=[r for r in active if pd.Timestamp(r.exit_time)>et]
  for _,r in g.sort_values(['_priority','module']).iterrows():
   if any(str(a.side)==str(r.side) for a in active):continue
   risk_after=(len(active)+1)*PORTFOLIO_RULES['risk_per_sleeve']
   leverage_after=(len(active)+1)*PORTFOLIO_RULES['leverage_per_sleeve_upper_bound']
   if risk_after>PORTFOLIO_RULES['max_total_risk']+1e-15:continue
   if leverage_after>PORTFOLIO_RULES['max_total_leverage']+1e-15:continue
   r=r.copy();r['portfolio_rule_id']=PORTFOLIO_RULES['rule_id'];r['portfolio_nominal_risk_after']=risk_after;r['portfolio_nominal_leverage_after']=leverage_after
   accepted.append(r);active.append(r)
 if not accepted:return z.iloc[0:0].drop(columns=['_priority'])
 return pd.DataFrame(accepted).drop(columns=['_priority']).sort_values(['entry_time','module']).reset_index(drop=True)

def module_trades(f,s,fee=.0004,slip=.0001,entry_delay_bars=0,extra_entry_penalty=0.):
 z=[]
 def add(name,t):
  if len(t):t=t.copy();t['module']=name;z.append(t)
 vceL=[x for x in e.central_specs() if x.architecture=='VOLATILITY_COMPRESSION_EXPANSION' and x.side=='LONG'][0]
 vceS=[x for x in e.central_specs() if x.architecture=='VOLATILITY_COMPRESSION_EXPANSION' and x.side=='SHORT'][0]
 momL=[x for x in e.central_specs() if x.architecture=='MOMENTUM_ACCELERATION' and x.side=='LONG'][0]
 dreS=[x for x in p2.central_specs() if x.architecture=='DIRECTIONAL_RANGE_EXPANSION' and x.side=='SHORT'][0]
 folS=[x for x in p4.central_specs() if x.architecture=='BREAKOUT_FOLLOW_THROUGH_CONFIRMATION' and x.side=='SHORT'][0]
 dreS=replace(dreS,body_atr_min=s.dre_short_body,range_atr_min=s.dre_short_range)
 if s.architecture=='ASYMMETRIC_MULTI_PATTERN_ENSEMBLE':
  vceL=replace(vceL,rel_volume_min=s.vce_long_rel_volume);vceS=replace(vceS,rel_volume_min=s.vce_short_rel_volume);folS=replace(folS,confirm_body_atr_min=s.follow_confirm_body)
  add('VCE_LONG',e.run_backtest(f,vceL,fee=fee,slip=slip,entry_delay_bars=entry_delay_bars,extra_entry_penalty=extra_entry_penalty)[0]);add('VCE_SHORT',e.run_backtest(f,vceS,fee=fee,slip=slip,entry_delay_bars=entry_delay_bars,extra_entry_penalty=extra_entry_penalty)[0]);add('DRE_SHORT',p2.run_backtest(f,dreS,fee=fee,slip=slip,entry_delay_bars=entry_delay_bars,extra_entry_penalty=extra_entry_penalty)[0]);add('FOLLOW_SHORT',p4.run_backtest(f,folS,fee=fee,slip=slip,entry_delay_bars=entry_delay_bars,extra_entry_penalty=extra_entry_penalty)[0])
 else:
  momL=replace(momL,momentum_atr_min=s.momentum_long_threshold)
  add('MOMENTUM_LONG',e.run_backtest(f,momL,fee=fee,slip=slip,entry_delay_bars=entry_delay_bars,extra_entry_penalty=extra_entry_penalty)[0]);add('DRE_SHORT',p2.run_backtest(f,dreS,fee=fee,slip=slip,entry_delay_bars=entry_delay_bars,extra_entry_penalty=extra_entry_penalty)[0])
 if not z:return pd.DataFrame()
 t=pd.concat(z,ignore_index=True).sort_values(['entry_time','module']).reset_index(drop=True);t['architecture']=s.architecture
 return apply_portfolio_rules(t)

def side_metrics(t):
 return {'LONG':e.metrics(t[t.side=='LONG']) if len(t) else e.metrics(t),'SHORT':e.metrics(t[t.side=='SHORT']) if len(t) else e.metrics(t),'COMBINED':e.metrics(t)}

def main():
 ap=argparse.ArgumentParser();ap.add_argument('corpus',type=Path);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--protocol',type=Path,required=True);a=ap.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 ah=verify(a.protocol/'ROUND23_PHASE6_ARCHITECTURES_LOCKED.json');ch=e.verify_lock(a.protocol/'ROUND23_CRITERIA_LOCKED.json')
 crit=json.loads((a.protocol/'ROUND23_CRITERIA_LOCKED.json').read_text());f=e.prepare_features(e.load_ohlcv(a.corpus));reports=[]
 for c in specs():
  od=a.output/c.architecture;od.mkdir(exist_ok=True);variants=[];nr=[]
  for j,s in enumerate(neighbours(c)):
   t=module_trades(f,s);m=e.metrics(t);variants.append((s,t));nr.append({'variant':j,'spec':asdict(s),**m,'passes_basic':bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.1 and m['expectancy_r']>0)})
  ct=variants[0][1];mo=e.month_table(ct);qu=e.quarter_table(ct);rg=e.regime_table(ct);yr=e.year_table(ct)
  nt,nf=e.nested_monthly(variants);at,af=e.expanding_annual(variants);bt,bf=e.blocked_year_cv(variants)
  stress_trades={}
  for r in crit['stress_matrix']:
   stress_trades[r['id']]=module_trades(f,c,fee=r['fee'],slip=r['slippage'],entry_delay_bars=r['entry_delay_bars'],extra_entry_penalty=r['extra_entry_penalty'])
  gates={key:e.scoped_gate_report(ct,nt,at,bt,stress_trades,variants,crit,side) for key,side in (('LONG','LONG'),('SHORT','SHORT'),('COMBINED',None))}
  passes_all=all(x['passes_all'] for x in gates.values());mc=e.monte_carlo(ct)
  ct.to_csv(od/'central_trades.csv',index=False);mo.to_csv(od/'central_months.csv',index=False);qu.to_csv(od/'central_rolling_quarters.csv',index=False);rg.to_csv(od/'central_regimes.csv',index=False);yr.to_csv(od/'central_years.csv',index=False);nt.to_csv(od/'purged_monthly_outer_trades.csv',index=False);nf.to_json(od/'purged_monthly_folds.json',orient='records',indent=2);at.to_csv(od/'expanding_annual_outer_trades.csv',index=False);af.to_json(od/'expanding_annual_folds.json',orient='records',indent=2);bt.to_csv(od/'blocked_year_outer_trades.csv',index=False);bf.to_json(od/'blocked_year_folds.json',orient='records',indent=2);pd.DataFrame(nr).to_json(od/'neighbours.json',orient='records',indent=2)
  rep={'central_spec':asdict(c),'portfolio_rules':PORTFOLIO_RULES,'central':e.metrics(ct),'direction_metrics':side_metrics(ct),'purged_monthly_outer':e.metrics(nt),'purged_monthly_direction_metrics':side_metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'stress':{k:e.metrics(v) for k,v in stress_trades.items()},'temporal_block_bootstrap':e.bootstrap_month(ct),'temporal_cluster_monte_carlo':mc,'gates_long_short_combined':gates}
  (od/'REPORT.json').write_text(json.dumps(e.json_safe(rep),indent=2))
  reports.append({'architecture':c.architecture,'side':'LONG_SHORT_COMBINED_LOCKED','passes_all':passes_all,'central':e.metrics(ct),'direction_metrics':side_metrics(ct),'purged_monthly_outer':e.metrics(nt),'expanding_annual_outer':e.metrics(at),'blocked_year_outer':e.metrics(bt),'failed_checks':{k:v['diagnostics']['failed_checks'] for k,v in gates.items()},'report_path':str(od/'REPORT.json'),'minimum_annual_expectancy':float(af[af.outer_trades>0].outer_expectancy_r.min()) if len(af) and (af.outer_trades>0).any() else -math.inf,'mc_q95_dd':mc['q95_max_drawdown_abs']})
 d={'phase':'ROUND23_PHASE6_CORRECTED','stable':any(x['passes_all'] for x in reports),'admitted_count':sum(x['passes_all'] for x in reports),'round22_holdout_used':False,'scalp_engine_touched':False,'android_integration_allowed':False,'portfolio_rules':PORTFOLIO_RULES,'protocol_hashes':{'phase6_architectures':ah,'criteria':ch},'candidate_reports':reports}
 (a.output/'PHASE6_DECISION.json').write_text(json.dumps(e.json_safe(d),indent=2));print(json.dumps(e.json_safe(d),indent=2))
if __name__=='__main__':main()
