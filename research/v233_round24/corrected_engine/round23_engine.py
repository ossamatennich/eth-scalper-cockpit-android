#!/usr/bin/env python3
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal

import numpy as np
import pandas as pd

Side = Literal['LONG', 'SHORT']
START = pd.Timestamp('2021-01-01T00:00:00Z')
END = pd.Timestamp('2026-06-01T00:00:00Z')
SEED = 23320260718


@dataclass(frozen=True)
class Spec:
    architecture: str
    side: Side
    regime_threshold: float = 0.0
    h4_adx_min: float = 0.0
    h4_adx_max: float = 99.0
    range_score_abs_max: float = 99.0
    pullback_lookback: int = 3
    resume_break_bars: int = 2
    compression_bars: int = 12
    compression_range_atr_max: float = 3.2
    bandwidth_max: float = 0.055
    expansion_range_atr_min: float = 1.15
    close_location_min: float = 0.72
    momentum_bars: int = 6
    momentum_atr_min: float = 1.4
    channel_bars: int = 20
    cross_age_max: int = 4
    impulse_break_bars: int = 4
    body_atr_min: float = 0.35
    bb_period: int = 20
    bb_std: float = 2.2
    reentry_buffer_atr: float = 0.05
    rel_volume_min: float = 0.0
    rel_volume_max: float = 99.0
    atr_stop_mult: float = 1.35
    atr_stop_cap: float = 3.0
    reward_risk: float = 2.0
    max_hold_bars: int = 16
    breakeven_r: float = 1.0
    trail_start_r: float = 1.6
    trail_atr_mult: float = 1.8
    cooldown_bars: int = 4
    risk_pct: float = 0.0015
    max_leverage: float = 1.0


def json_safe(v):
    if isinstance(v, dict): return {str(k): json_safe(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)): return [json_safe(x) for x in v]
    if isinstance(v, (np.integer,)): return int(v)
    if isinstance(v, (np.floating,)): return None if not np.isfinite(v) else float(v)
    if isinstance(v, (pd.Timestamp,)): return str(v)
    if isinstance(v, float) and not math.isfinite(v): return None
    return v


def verify_lock(path: Path) -> str:
    got = hashlib.sha256(path.read_bytes()).hexdigest()
    expected = path.with_suffix('.sha256').read_text(encoding='utf-8').split()[0]
    if got != expected:
        raise RuntimeError(f'lock mismatch: {path}')
    return got


def load_ohlcv(path: Path) -> pd.DataFrame:
    x = pd.read_csv(path)
    x['timestamp'] = pd.to_datetime(x['timestamp'], utc=True)
    for c in ('open','high','low','close','volume'):
        x[c] = pd.to_numeric(x[c], errors='coerce')
    x = x.dropna().sort_values('timestamp').set_index('timestamp')
    x = x[(x.index >= START) & (x.index < END)]
    if len(x) != 47448 or x.index.min() != START or x.index.max() != END - pd.Timedelta(hours=1):
        raise RuntimeError(f'corpus boundary mismatch rows={len(x)} min={x.index.min()} max={x.index.max()}')
    if x.index.duplicated().any(): raise RuntimeError('duplicate timestamps')
    expected = pd.date_range(START, END - pd.Timedelta(hours=1), freq='1h')
    if not x.index.equals(expected): raise RuntimeError('1h continuity mismatch')
    bad = ((x[['open','high','low','close']] <= 0).any(axis=1) | (x.volume < 0) |
           (x.high < x[['open','close']].max(axis=1)) | (x.low > x[['open','close']].min(axis=1)) | (x.high < x.low))
    if bad.any(): raise RuntimeError(f'invalid OHLC rows: {int(bad.sum())}')
    return x


def wilder(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(alpha=1/n, adjust=False, min_periods=n).mean()


def atr_adx(x: pd.DataFrame, n: int = 14):
    prev = x.close.shift(1)
    tr = pd.concat([(x.high-x.low), (x.high-prev).abs(), (x.low-prev).abs()], axis=1).max(axis=1)
    atr = wilder(tr, n)
    up, down = x.high.diff(), -x.low.diff()
    plus = pd.Series(np.where((up>down)&(up>0), up, 0.0), index=x.index)
    minus = pd.Series(np.where((down>up)&(down>0), down, 0.0), index=x.index)
    pdi = 100*wilder(plus,n)/atr
    mdi = 100*wilder(minus,n)/atr
    dx = 100*(pdi-mdi).abs()/(pdi+mdi).replace(0,np.nan)
    return atr, wilder(dx,n)


def rsi(s: pd.Series, n: int = 14):
    d = s.diff(); up = d.clip(lower=0); dn = -d.clip(upper=0)
    rs = wilder(up,n)/wilder(dn,n).replace(0,np.nan)
    return 100 - 100/(1+rs)


def prepare_features(raw: pd.DataFrame) -> pd.DataFrame:
    x = raw.copy()
    x['atr'], x['adx'] = atr_adx(x,14)
    x['atr_pct'] = x.atr/x.close
    for n in (9,20,21,50,55): x[f'ema{n}'] = x.close.ewm(span=n, adjust=False, min_periods=n).mean()
    x['rsi14'] = rsi(x.close,14)
    x['vol_sma24'] = x.volume.rolling(24,min_periods=24).mean()
    x['rel_volume'] = x.volume/x.vol_sma24
    x['body_atr'] = (x.close-x.open)/x.atr
    x['range_atr'] = (x.high-x.low)/x.atr
    span = (x.high-x.low).replace(0,np.nan)
    x['clv_long'] = (x.close-x.low)/span
    x['clv_short'] = (x.high-x.close)/span
    for n in (2,3,4,6,8,10,12,14,20,24,36):
        x[f'prior_high_{n}'] = x.high.shift(1).rolling(n,min_periods=n).max()
        x[f'prior_low_{n}'] = x.low.shift(1).rolling(n,min_periods=n).min()
    for n in (8,12,20):
        x[f'swing_high_{n}'] = x.high.shift(1).rolling(n,min_periods=n).max()
        x[f'swing_low_{n}'] = x.low.shift(1).rolling(n,min_periods=n).min()
    for n in (3,6,12): x[f'ret_{n}'] = x.close/x.close.shift(n)-1
    x['bb_mid'] = x.close.rolling(20,min_periods=20).mean()
    x['bb_sd'] = x.close.rolling(20,min_periods=20).std(ddof=0)
    x['bb_upper_22'] = x.bb_mid + 2.2*x.bb_sd
    x['bb_lower_22'] = x.bb_mid - 2.2*x.bb_sd
    x['bb_upper_20'] = x.bb_mid + 2.0*x.bb_sd
    x['bb_lower_20'] = x.bb_mid - 2.0*x.bb_sd
    x['bb_bandwidth'] = (x['bb_upper_20']-x['bb_lower_20'])/x.bb_mid
    for n in (10,12,14):
        hi = x.high.shift(1).rolling(n,min_periods=n).max()
        lo = x.low.shift(1).rolling(n,min_periods=n).min()
        x[f'compression_range_atr_{n}'] = (hi-lo)/x.atr
    cross_up = (x.ema9 > x.ema21) & (x.ema9.shift(1) <= x.ema21.shift(1))
    cross_dn = (x.ema9 < x.ema21) & (x.ema9.shift(1) >= x.ema21.shift(1))
    for max_age in (3,4,5):
        x[f'cross_up_recent_{max_age}'] = cross_up.rolling(max_age,min_periods=1).max().fillna(0).astype(bool)
        x[f'cross_dn_recent_{max_age}'] = cross_dn.rolling(max_age,min_periods=1).max().fillna(0).astype(bool)

    # F06: zero-volume placeholders are not legitimate OHLC observations.  Their
    # prices are excluded from the 4 h aggregate while their zero volume remains
    # harmless.  This is deterministic and keeps the 4 h bar available only
    # after all four constituent 1 h bars have closed.
    h4_source = x[['open','high','low','close','volume']].copy()
    zero_volume = h4_source.volume <= 0
    h4_source.loc[zero_volume, ['open','high','low','close']] = np.nan
    h4 = h4_source.resample('4h',label='left',closed='left',origin='epoch').agg(
        open=('open','first'), high=('high','max'), low=('low','min'), close=('close','last'), volume=('volume','sum')).dropna(subset=['open','high','low','close'])
    h4['atr'], h4['adx'] = atr_adx(h4,14)
    h4['ema12'] = h4.close.ewm(span=12,adjust=False,min_periods=12).mean()
    h4['ema36'] = h4.close.ewm(span=36,adjust=False,min_periods=36).mean()
    gap = (h4.ema12-h4.ema36)/h4.atr.replace(0,np.nan)
    slope = (h4.ema12-h4.ema12.shift(1))/h4.atr.replace(0,np.nan)
    strength = ((h4.adx-18)/20).clip(-1,1.5)
    h4['trend_score'] = np.tanh(.75*gap+.55*slope+.25*np.sign(gap)*strength)
    h4['direction'] = np.where(h4.trend_score>.2,'BULL',np.where(h4.trend_score<-.2,'BEAR','RANGE'))
    h4['transition'] = (np.sign(h4.trend_score)!=np.sign(h4.trend_score.shift(1))) | (h4.trend_score.abs()<.2)
    available = h4[['atr','adx','trend_score','direction','transition']].copy()
    available.index = available.index + pd.Timedelta(hours=4)
    available.index.name = 'h4_available_time'
    sig = pd.DataFrame({'signal_close_time': x.index+pd.Timedelta(hours=1)},index=x.index)
    m = pd.merge_asof(sig.reset_index().sort_values('signal_close_time'),available.reset_index().sort_values('h4_available_time'),
                      left_on='signal_close_time',right_on='h4_available_time',direction='backward').set_index('timestamp')
    for c in available.columns: x[f'h4_{c}'] = m[c].reindex(x.index)
    x['h4_available_time'] = m.h4_available_time.reindex(x.index)
    x['vol_regime'] = np.where(x.atr_pct>=.015,'HIGH',np.where(x.atr_pct<=.006,'LOW','MID'))
    trans = x.h4_transition.astype('boolean').fillna(True).to_numpy(bool)
    x['market_regime'] = np.where(trans,'TRANSITION',x.h4_direction.fillna('RANGE'))
    return x


def central_specs() -> list[Spec]:
    specs=[]
    for side in ('LONG','SHORT'):
        specs.extend([
            Spec('TREND_PULLBACK_RESUMPTION',side,regime_threshold=.25,h4_adx_min=18,pullback_lookback=3,resume_break_bars=2,rel_volume_min=.75,atr_stop_mult=1.35,atr_stop_cap=3,reward_risk=2,max_hold_bars=18,breakeven_r=1,trail_start_r=1.6,trail_atr_mult=1.8,cooldown_bars=4),
            Spec('VOLATILITY_COMPRESSION_EXPANSION',side,regime_threshold=.10,compression_bars=12,compression_range_atr_max=3.2,bandwidth_max=.055,expansion_range_atr_min=1.15,close_location_min=.72,rel_volume_min=1.10,atr_stop_mult=1.25,atr_stop_cap=3,reward_risk=2.2,max_hold_bars=14,breakeven_r=1,trail_start_r=1.7,trail_atr_mult=1.7,cooldown_bars=6),
            Spec('RANGE_EXTREME_REVERSION',side,range_score_abs_max=.25,h4_adx_max=23,bb_period=20,bb_std=2.2,reentry_buffer_atr=.05,rel_volume_max=1.6,atr_stop_mult=1,atr_stop_cap=2.6,reward_risk=1.35,max_hold_bars=10,breakeven_r=.65,trail_start_r=1,trail_atr_mult=1.4,cooldown_bars=4),
            Spec('MOMENTUM_ACCELERATION',side,regime_threshold=.35,h4_adx_min=20,momentum_bars=6,momentum_atr_min=1.4,channel_bars=20,close_location_min=.72,rel_volume_min=1.2,atr_stop_mult=1.5,atr_stop_cap=3.2,reward_risk=2.4,max_hold_bars=16,breakeven_r=1.1,trail_start_r=1.8,trail_atr_mult=1.9,cooldown_bars=5),
            Spec('DUAL_EMA_IMPULSE',side,regime_threshold=.22,h4_adx_min=17,cross_age_max=4,impulse_break_bars=4,body_atr_min=.35,rel_volume_min=.9,atr_stop_mult=1.35,atr_stop_cap=3,reward_risk=2,max_hold_bars=16,breakeven_r=.9,trail_start_r=1.5,trail_atr_mult=1.7,cooldown_bars=4)
        ])
    return specs


def neighbours(s: Spec) -> list[Spec]:
    out=[s]
    def add(field, vals):
        nonlocal out
        for v in vals: out.append(replace(s, **{field:v}))
    if s.architecture=='TREND_PULLBACK_RESUMPTION':
        add('regime_threshold',[.20,.30]); add('pullback_lookback',[2,4]); add('rel_volume_min',[.65,.9]); add('atr_stop_mult',[1.2,1.5]); add('reward_risk',[1.8,2.2]); add('max_hold_bars',[14,22])
    elif s.architecture=='VOLATILITY_COMPRESSION_EXPANSION':
        add('compression_bars',[10,14]); add('compression_range_atr_max',[2.8,3.6]); add('expansion_range_atr_min',[1.0,1.3]); add('rel_volume_min',[1.0,1.25]); add('reward_risk',[2.0,2.4]); add('max_hold_bars',[12,18])
    elif s.architecture=='RANGE_EXTREME_REVERSION':
        add('range_score_abs_max',[.20,.30]); add('h4_adx_max',[20.,26.]); add('bb_std',[2.0,2.4]); add('atr_stop_mult',[.85,1.15]); add('reward_risk',[1.15,1.55]); add('max_hold_bars',[8,12])
    elif s.architecture=='MOMENTUM_ACCELERATION':
        add('regime_threshold',[.30,.40]); add('momentum_bars',[3,12]); add('momentum_atr_min',[1.2,1.6]); add('channel_bars',[12,24]); add('rel_volume_min',[1.05,1.35]); add('reward_risk',[2.1,2.7]); add('max_hold_bars',[12,20])
    elif s.architecture=='DUAL_EMA_IMPULSE':
        add('regime_threshold',[.17,.27]); add('cross_age_max',[3,5]); add('impulse_break_bars',[3,6]); add('body_atr_min',[.25,.45]); add('rel_volume_min',[.75,1.05]); add('reward_risk',[1.8,2.2]); add('max_hold_bars',[12,20])
    uniq={json.dumps(asdict(z),sort_keys=True):z for z in out}
    return list(uniq.values())


def _stop_and_distance(f: pd.DataFrame, i: int, s: Spec, swing_n: int=12, extra: float=0.0):
    c=float(f.close.iloc[i]); a=float(f.atr.iloc[i])
    if s.side=='LONG':
        swing=float(f[f'swing_low_{swing_n}'].iloc[i]); stop=min(swing-extra*a,c-s.atr_stop_mult*a); dist=c-stop
    else:
        swing=float(f[f'swing_high_{swing_n}'].iloc[i]); stop=max(swing+extra*a,c+s.atr_stop_mult*a); dist=stop-c
    return stop,dist,a


def setup_events(f: pd.DataFrame, s: Spec) -> list[dict]:
    n=len(f); valid=np.zeros(n,dtype=bool); target=np.full(n,np.nan); stop=np.full(n,np.nan)
    C=f.close.to_numpy(float); O=f.open.to_numpy(float); H=f.high.to_numpy(float); L=f.low.to_numpy(float); A=f.atr.to_numpy(float)
    rv=f.rel_volume.to_numpy(float); score=pd.to_numeric(f.h4_trend_score,errors='coerce').fillna(0).to_numpy(float)
    h4adx=pd.to_numeric(f.h4_adx,errors='coerce').fillna(np.nan).to_numpy(float)
    transition=f.h4_transition.astype('boolean').fillna(True).to_numpy(bool)
    common=np.isfinite(A)&(f.atr_pct.to_numpy(float)>=.0025)&(f.atr_pct.to_numpy(float)<=.05)&np.isfinite(rv)
    dir_ok=(score>=s.regime_threshold) if s.side=='LONG' else (score<=-s.regime_threshold)

    if s.architecture=='TREND_PULLBACK_RESUMPTION':
        ema20=f.ema20.to_numpy(float); ema50=f.ema50.to_numpy(float)
        aligned=(ema20>ema50) if s.side=='LONG' else (ema20<ema50)
        touched=np.zeros(n,dtype=bool)
        for k in range(1,s.pullback_lookback+1):
            if s.side=='LONG': touched |= np.roll(L<=ema20, k)
            else: touched |= np.roll(H>=ema20, k)
        touched[:s.pullback_lookback+2]=False
        if s.side=='LONG': resume=(C>f[f'prior_high_{s.resume_break_bars}'].to_numpy(float))&(C>O)&(C>ema20)
        else: resume=(C<f[f'prior_low_{s.resume_break_bars}'].to_numpy(float))&(C<O)&(C<ema20)
        valid=common&dir_ok&(h4adx>=s.h4_adx_min)&(~transition)&aligned&touched&resume&(rv>=s.rel_volume_min)
        swing_n=12
    elif s.architecture=='VOLATILITY_COMPRESSION_EXPANSION':
        cr=f[f'compression_range_atr_{s.compression_bars}'].to_numpy(float)
        bw=f.bb_bandwidth.to_numpy(float); rng=f.range_atr.to_numpy(float)
        if s.side=='LONG': br=C>f[f'prior_high_{s.compression_bars}'].to_numpy(float); clv=f.clv_long.to_numpy(float)
        else: br=C<f[f'prior_low_{s.compression_bars}'].to_numpy(float); clv=f.clv_short.to_numpy(float)
        valid=common&dir_ok&(cr<=s.compression_range_atr_max)&(bw<=s.bandwidth_max)&(rng>=s.expansion_range_atr_min)&(clv>=s.close_location_min)&(rv>=s.rel_volume_min)&br
        swing_n=8
    elif s.architecture=='RANGE_EXTREME_REVERSION':
        # F04: bb_period and bb_std are real parameters; no fixed 2.2-sigma
        # feature is used behind a configurable specification.
        mid_s=f.close.rolling(s.bb_period,min_periods=s.bb_period).mean()
        sd_s=f.close.rolling(s.bb_period,min_periods=s.bb_period).std(ddof=0)
        mid=mid_s.to_numpy(float); upper=(mid_s+s.bb_std*sd_s).to_numpy(float); lower=(mid_s-s.bb_std*sd_s).to_numpy(float)
        range_ok=(np.abs(score)<=s.range_score_abs_max)&(h4adx<=s.h4_adx_max)
        if s.side=='LONG':
            extreme=np.roll(C<lower,1); extreme[0]=False
            reenter=(C>lower-s.reentry_buffer_atr*A)&(C>O)&(f.rsi14.to_numpy(float)<48)
            target=mid.copy()
        else:
            extreme=np.roll(C>upper,1); extreme[0]=False
            reenter=(C<upper+s.reentry_buffer_atr*A)&(C<O)&(f.rsi14.to_numpy(float)>52)
            target=mid.copy()
        valid=common&range_ok&extreme&reenter&(rv<=s.rel_volume_max)
        swing_n=8
    elif s.architecture=='MOMENTUM_ACCELERATION':
        ema20=f.ema20.to_numpy(float); ema50=f.ema50.to_numpy(float)
        aligned=(ema20>ema50) if s.side=='LONG' else (ema20<ema50)
        mom=f[f'ret_{s.momentum_bars}'].to_numpy(float); threshold=s.momentum_atr_min*f.atr_pct.to_numpy(float)
        if s.side=='LONG': accel=mom>=threshold; br=C>f[f'prior_high_{s.channel_bars}'].to_numpy(float); clv=f.clv_long.to_numpy(float)
        else: accel=mom<=-threshold; br=C<f[f'prior_low_{s.channel_bars}'].to_numpy(float); clv=f.clv_short.to_numpy(float)
        valid=common&dir_ok&(h4adx>=s.h4_adx_min)&(~transition)&aligned&accel&br&(clv>=s.close_location_min)&(rv>=s.rel_volume_min)
        swing_n=12
    elif s.architecture=='DUAL_EMA_IMPULSE':
        aligned=(f.ema9.to_numpy(float)>f.ema21.to_numpy(float)) if s.side=='LONG' else (f.ema9.to_numpy(float)<f.ema21.to_numpy(float))
        recent=f[f'cross_up_recent_{s.cross_age_max}'].to_numpy(bool) if s.side=='LONG' else f[f'cross_dn_recent_{s.cross_age_max}'].to_numpy(bool)
        if s.side=='LONG': imp=(C>f[f'prior_high_{s.impulse_break_bars}'].to_numpy(float))&(f.body_atr.to_numpy(float)>=s.body_atr_min)
        else: imp=(C<f[f'prior_low_{s.impulse_break_bars}'].to_numpy(float))&(f.body_atr.to_numpy(float)<=-s.body_atr_min)
        valid=common&dir_ok&(h4adx>=s.h4_adx_min)&(~transition)&aligned&recent&imp&(rv>=s.rel_volume_min)
        swing_n=8
    else: raise ValueError(s.architecture)

    warm=120; valid[:warm]=False
    events=[]
    for i in np.flatnonzero(valid):
        st,dist,a=_stop_and_distance(f,int(i),s,swing_n,extra=.15 if s.architecture=='RANGE_EXTREME_REVERSION' else 0)
        if not (np.isfinite(st) and np.isfinite(dist) and np.isfinite(a) and s.atr_stop_mult*a<=dist<=s.atr_stop_cap*a): continue
        ev={'signal_i':int(i),'complete_i':int(i),'stop':float(st)}
        if s.architecture=='RANGE_EXTREME_REVERSION' and np.isfinite(target[i]): ev['target_level']=float(target[i])
        events.append(ev)
    return events


def adverse(price:float, side:Side, action:str, slip:float, extra:float=0.0):
    total=slip+(extra if action=='ENTRY' else 0)
    if side=='LONG': return price*(1+total) if action=='ENTRY' else price*(1-slip)
    return price*(1-total) if action=='ENTRY' else price*(1+slip)


def trigger_fill(side:Side, kind:str, bar_open:float, level:float, slip:float):
    raw=(min(bar_open,level) if side=='LONG' else max(bar_open,level)) if kind=='STOP' else level
    return adverse(raw,side,'EXIT',slip)


def run_backtest(f:pd.DataFrame,s:Spec,start:pd.Timestamp=START,end:pd.Timestamp=END,fee:float=.0004,slip:float=.0001,entry_delay_bars:int=0,extra_entry_penalty:float=0.0):
    if end>END: raise RuntimeError('boundary exceeded')
    events=setup_events(f,s); by={}
    for ev in events: by.setdefault(ev['complete_i']+1+entry_delay_bars,[]).append(ev)
    O,H,L,C,A=(f[c].to_numpy(float) for c in ('open','high','low','close','atr')); idx=f.index
    si=int(idx.searchsorted(start)); ei=int(idx.searchsorted(end)); equity=100000.;peak=equity;maxdd=0.;pos=None;cool=-1;rows=[]
    def close(p,i,reason,px):
        nonlocal equity,peak,maxdd,cool
        gross=(px-p['entry'])*p['qty'] if p['side']=='LONG' else (p['entry']-px)*p['qty']
        fees=fee*(p['entry']+px)*p['qty']; net=gross-fees; equity+=net; risk_cash=p['risk_unit']*p['qty']
        exit_at_open=reason=='MAX_HOLD'
        rows.append({'architecture':s.architecture,'side':s.side,'signal_time':idx[p['signal_i']],'entry_time':idx[p['entry_i']],'exit_time':idx[i],
                     'entry':p['entry'],'exit':px,'stop_initial':p['stop_initial'],'target_initial':p['target_initial'],'exit_reason':reason,
                     'bars_held':i-p['entry_i'] if exit_at_open else i-p['entry_i']+1,
                     'completed_bars_before_exit':i-p['entry_i'],'exit_at_bar_open':exit_at_open,
                     'net_pnl':net,'fees':fees,'r_net':net/risk_cash if risk_cash else 0.,'equity_after':equity,
                     'market_regime':p['market_regime'],'vol_regime':p['vol_regime'],'h4_trend_score':p['h4_trend_score']})
        cool=i+s.cooldown_bars; peak=max(peak,equity); maxdd=min(maxdd,equity/peak-1)
    def exit_check(p,i,allow_time=True):
        # F08: max_hold_bars=N means N complete bars may elapse.  The time exit
        # occurs at the open of bar entry_i+N, before that bar's high/low range.
        completed=i-p['entry_i']
        if allow_time and completed>=s.max_hold_bars: return 'MAX_HOLD',adverse(O[i],s.side,'EXIT',slip)
        sh=L[i]<=p['stop'] if s.side=='LONG' else H[i]>=p['stop']; th=H[i]>=p['target'] if s.side=='LONG' else L[i]<=p['target']
        if sh or th:
            reason='STOP' if sh else 'TARGET'; level=p['stop'] if sh else p['target']; return reason,trigger_fill(s.side,reason,O[i],level,slip)
        return None,None
    def trail(p,i):
        if s.side=='LONG': p['best']=max(p['best'],H[i]); fav=p['best']-p['entry']
        else: p['best']=min(p['best'],L[i]); fav=p['entry']-p['best']
        r=fav/max(p['initial_distance'],1e-12); ns=p['stop']
        if r>=s.breakeven_r: ns=max(ns,p['entry']) if s.side=='LONG' else min(ns,p['entry'])
        if r>=s.trail_start_r and np.isfinite(A[i]):
            tr=p['best']-A[i]*s.trail_atr_mult if s.side=='LONG' else p['best']+A[i]*s.trail_atr_mult
            ns=max(ns,tr) if s.side=='LONG' else min(ns,tr)
        p['stop']=ns
    for i in range(max(1,si),min(ei,len(f))):
        if pos is not None:
            reason,px=exit_check(pos,i,True)
            if reason: close(pos,i,reason,float(px));pos=None
            else: trail(pos,i)
            continue
        if i<cool or i not in by: continue
        ev=max(by[i],key=lambda z:z['complete_i']); sig=ev['complete_i']
        if sig<si or i>=ei: continue
        entry=adverse(O[i],s.side,'ENTRY',slip,extra_entry_penalty); st=float(ev['stop']); dist=entry-st if s.side=='LONG' else st-entry
        if not np.isfinite(dist) or dist<=0 or dist>A[sig]*s.atr_stop_cap*1.20: continue
        stop_exit=trigger_fill(s.side,'STOP',st,st,slip); risk_unit=(entry-stop_exit if s.side=='LONG' else stop_exit-entry)+fee*(entry+stop_exit)
        qty=min(equity*s.risk_pct/max(risk_unit,1e-12),equity*s.max_leverage/max(entry,1e-12))
        if qty<=0: continue
        if 'target_level' in ev:
            tgt=float(ev['target_level'])
            if (s.side=='LONG' and tgt<=entry) or (s.side=='SHORT' and tgt>=entry): continue
            rr_target=entry+dist*s.reward_risk if s.side=='LONG' else entry-dist*s.reward_risk
            tgt=min(tgt,rr_target) if s.side=='LONG' else max(tgt,rr_target)
        else: tgt=entry+dist*s.reward_risk if s.side=='LONG' else entry-dist*s.reward_risk
        row=f.iloc[sig]
        pos={'signal_i':sig,'entry_i':i,'entry':entry,'qty':qty,'risk_unit':risk_unit,'initial_distance':dist,'stop':st,'stop_initial':st,
             'target':tgt,'target_initial':tgt,'best':entry,'side':s.side,'market_regime':str(row.market_regime),'vol_regime':str(row.vol_regime),'h4_trend_score':float(row.h4_trend_score)}
        reason,px=exit_check(pos,i,False)
        if reason: close(pos,i,reason,float(px));pos=None
        else: trail(pos,i)
    if pos is not None and min(ei,len(f))>0:
        fi=min(ei,len(f))-1; close(pos,fi,'END_OF_WINDOW',adverse(C[fi],s.side,'EXIT',slip));pos=None
    t=pd.DataFrame(rows)
    if len(t):
        for c in ('signal_time','entry_time','exit_time'): t[c]=pd.to_datetime(t[c],utc=True)
    return t,metrics(t)


def pf(t):
    if len(t)==0:return None
    b=t.net_pnl if 'net_pnl' in t else t.r_net; w=b>0; loss=float(-b[~w].sum()); return float(b[w].sum())/loss if loss>0 else None


def metrics(t):
    if len(t)==0:return {'trades':0,'profit_factor':None,'expectancy_r':0.,'return_r':0.,'win_rate_pct':0.,'max_drawdown_r':0.}
    r=t.r_net.to_numpy(float); curve=np.cumsum(r); peak=np.maximum.accumulate(np.r_[0.,curve])[-len(curve):];dd=curve-peak
    return {'trades':int(len(t)),'profit_factor':pf(t),'expectancy_r':float(r.mean()),'return_r':float(r.sum()),'win_rate_pct':float((r>0).mean()*100),'max_drawdown_r':float(dd.min(initial=0.))}


def slice_entries(t,start,end,require_exit_before=None):
    if len(t)==0:return t.copy()
    z=t[(t.entry_time>=start)&(t.entry_time<end)].copy()
    if require_exit_before is not None:z=z[z.exit_time<require_exit_before]
    return z


def month_table(t):
    rows=[]
    for st in pd.date_range(START,END-pd.offsets.MonthBegin(1),freq='MS',tz='UTC'):
        en=min(st+pd.offsets.MonthBegin(1),END);rows.append({'month':st.strftime('%Y-%m'),**metrics(slice_entries(t,st,en))})
    return pd.DataFrame(rows)


def quarter_table(t):
    rows=[]
    for st in pd.date_range(START,END-pd.offsets.MonthBegin(3),freq='MS',tz='UTC'):
        en=min(st+pd.offsets.MonthBegin(3),END);rows.append({'start':str(st),'end':str(en),**metrics(slice_entries(t,st,en))})
    return pd.DataFrame(rows)


def regime_table(t):
    if len(t)==0:return pd.DataFrame(columns=['market_regime','vol_regime','trades','profit_factor','expectancy_r','return_r'])
    return pd.DataFrame([{'market_regime':m,'vol_regime':v,**metrics(g)} for (m,v),g in t.groupby(['market_regime','vol_regime'],dropna=False)])


def year_table(t):
    rows=[]
    for y in range(2021,2027):
        st=pd.Timestamp(f'{y}-01-01',tz='UTC');en=min(pd.Timestamp(f'{y+1}-01-01',tz='UTC'),END)
        if st>=END:continue
        rows.append({'year':y,**metrics(slice_entries(t,st,en))})
    return pd.DataFrame(rows)


def select_score_cached(train, month_full, train_end=None):
    m=metrics(train)
    if m['trades']<20:return -1e9+m['trades']
    mt=month_full
    if train_end is not None:
        mt=mt[mt['month_start']<train_end]
    active=mt[mt.trades>0]
    if len(active)<8:return -1e9+m['trades']
    return float(active.expectancy_r.median()+.15*min(float(m['profit_factor'] or 0),2.5)+.20*(active.expectancy_r>0).mean()+.10*active.expectancy_r.quantile(.10))


def variant_cache(variants):
    out=[]
    for s,t in variants:
        mt=month_table(t)
        mt['month_start']=pd.to_datetime(mt.month+'-01',utc=True)
        out.append((s,t,mt))
    return out


def nested_monthly(variants,purge_h=48,embargo_h=24):
    folds=[];parts=[];cache=variant_cache(variants)
    for st in pd.date_range(pd.Timestamp('2022-01-01',tz='UTC'),END-pd.offsets.MonthBegin(1),freq='MS'):
        en=min(st+pd.offsets.MonthBegin(1),END);train_end=st-pd.Timedelta(hours=purge_h);test_st=st+pd.Timedelta(hours=embargo_h);test_en=en-pd.Timedelta(hours=embargo_h)
        scored=[]
        for s,t,mtab in cache:
            train=t[(t.entry_time<train_end)&(t.exit_time<train_end)].copy();scored.append((select_score_cached(train,mtab,train_end),json.dumps(asdict(s),sort_keys=True),s,t,metrics(train)))
        scored.sort(key=lambda z:(z[0],z[1]),reverse=True);score,_,chosen,all_t,tm=scored[0]
        # F07: a fold owns only trades fully resolved before its exclusive end.
        # Trades crossing the trailing embargo are excluded, never truncated or
        # attributed to the fold by entry time alone.
        outer=slice_entries(all_t,test_st,test_en,require_exit_before=test_en) if test_st<test_en else all_t.iloc[0:0]
        if len(outer):
            outer=outer.copy();outer['outer_fold']=st.strftime('%Y-%m');outer['selected_spec']=json.dumps(asdict(chosen),sort_keys=True);parts.append(outer)
        folds.append({'fold':st.strftime('%Y-%m'),'train_end_exclusive':str(train_end),'test_start':str(test_st),'test_end_exclusive':str(test_en),'selected_spec':asdict(chosen),'selection_score':score,'train':tm,'outer':metrics(outer)})
    return (pd.concat(parts,ignore_index=True) if parts else pd.DataFrame()),pd.DataFrame(folds)


def expanding_annual(variants,purge_h=48,embargo_h=24):
    rows=[];parts=[];cache=variant_cache(variants)
    for y in (2022,2023,2024,2025,2026):
        st=pd.Timestamp(f'{y}-01-01',tz='UTC');en=min(pd.Timestamp(f'{y+1}-01-01',tz='UTC'),END);train_end=st-pd.Timedelta(hours=purge_h);test_st=st+pd.Timedelta(hours=embargo_h);test_en=en-pd.Timedelta(hours=embargo_h)
        scored=[]
        for s,t,mtab in cache:
            train=t[(t.entry_time<train_end)&(t.exit_time<train_end)].copy();scored.append((select_score_cached(train,mtab,train_end),json.dumps(asdict(s),sort_keys=True),s,t,metrics(train)))
        scored.sort(key=lambda z:(z[0],z[1]),reverse=True);score,_,chosen,all_t,tm=scored[0];outer=slice_entries(all_t,test_st,test_en,require_exit_before=test_en)
        if len(outer):outer=outer.copy();outer['outer_fold']=f'Y{y}';outer['selected_spec']=json.dumps(asdict(chosen),sort_keys=True);parts.append(outer)
        rows.append({'fold':f'Y{y}','train_end_exclusive':str(train_end),'test_start':str(test_st),'test_end_exclusive':str(test_en),'selected_spec':asdict(chosen),'selection_score':score,'train':tm,**{f'outer_{k}':v for k,v in metrics(outer).items()}})
    return (pd.concat(parts,ignore_index=True) if parts else pd.DataFrame()),pd.DataFrame(rows)


def blocked_year_cv(variants,boundary_h=72):
    rows=[];parts=[];cache=variant_cache(variants)
    for y in (2021,2022,2023,2024,2025):
        st=pd.Timestamp(f'{y}-01-01',tz='UTC');en=pd.Timestamp(f'{y+1}-01-01',tz='UTC');test_st=st+pd.Timedelta(hours=boundary_h);test_en=en-pd.Timedelta(hours=boundary_h)
        scored=[]
        for s,t,mtab in cache:
            train=t[((t.exit_time<st-pd.Timedelta(hours=boundary_h))|(t.entry_time>=en+pd.Timedelta(hours=boundary_h)))].copy()
            mtrain=mtab[(mtab.month_start<st)|(mtab.month_start>=en)]
            scored.append((select_score_cached(train,mtrain,None),json.dumps(asdict(s),sort_keys=True),s,t,metrics(train)))
        scored.sort(key=lambda z:(z[0],z[1]),reverse=True);score,_,chosen,all_t,tm=scored[0];outer=slice_entries(all_t,test_st,test_en,require_exit_before=test_en)
        if len(outer):outer=outer.copy();outer['outer_fold']=f'BLOCK_Y{y}';outer['selected_spec']=json.dumps(asdict(chosen),sort_keys=True);parts.append(outer)
        rows.append({'fold':f'BLOCK_Y{y}','selected_spec':asdict(chosen),'selection_score':score,'train':tm,**{f'outer_{k}':v for k,v in metrics(outer).items()}})
    return (pd.concat(parts,ignore_index=True) if parts else pd.DataFrame()),pd.DataFrame(rows)


def bootstrap_iid(r,n=10000):
    if len(r)==0:return {'samples':n,'p_mean_positive':0.,'q10_mean_r':None}
    rng=np.random.default_rng(SEED);means=r[rng.integers(0,len(r),size=(n,len(r)))].mean(1)
    return {'samples':n,'p_mean_positive':float((means>0).mean()),'q10_mean_r':float(np.quantile(means,.1)),'q05_mean_r':float(np.quantile(means,.05))}


def bootstrap_month(t,n=10000):
    """Circular moving-block bootstrap over consecutive calendar months.

    F05: complete months are the atomic blocks.  Three-month consecutive
    sequences are sampled, preserving intra-month order, simultaneous sleeve
    losses and short-range serial dependence.
    """
    if len(t)==0:return {'samples':n,'block_months':3,'p_mean_positive':0.,'q10_mean_r':None,'q05_mean_r':None}
    z=t.copy();z['entry_time']=pd.to_datetime(z.entry_time,utc=True);z['month']=z.entry_time.dt.tz_localize(None).dt.to_period('M').astype(str)
    months=pd.period_range(z.entry_time.min().tz_localize(None).to_period('M'),z.entry_time.max().tz_localize(None).to_period('M'),freq='M').astype(str)
    g=z.groupby('month').r_net.agg(['sum','count']).reindex(months,fill_value=0.)
    sums=g['sum'].to_numpy(float);cnt=g['count'].to_numpy(float);m=len(sums);block=3
    rng=np.random.default_rng(SEED+1);means=np.empty(n);blocks=int(math.ceil(m/block))
    for j in range(n):
        starts=rng.integers(0,m,size=blocks);pick=np.concatenate([(np.arange(block)+q)%m for q in starts])[:m]
        means[j]=sums[pick].sum()/max(cnt[pick].sum(),1.)
    return {'samples':n,'block_months':block,'p_mean_positive':float((means>0).mean()),'q10_mean_r':float(np.quantile(means,.1)),'q05_mean_r':float(np.quantile(means,.05))}


def monte_carlo(data,n=10000,risk=.0015):
    """Temporal block drawdown simulation; never permutes individual trades."""
    if len(data)==0:return {'samples':n,'block_months':3,'cluster_key':'entry_time','q95_max_drawdown_abs':None}
    if isinstance(data,pd.DataFrame):
        z=data.copy();z['entry_time']=pd.to_datetime(z.entry_time,utc=True);z=z.sort_values(['entry_time']+(['module'] if 'module' in z else []))
        # Same-time sleeve outcomes form one portfolio shock.
        c=z.groupby('entry_time',sort=True).r_net.sum().rename('cluster_r').reset_index()
        c['month']=c.entry_time.dt.tz_localize(None).dt.to_period('M').astype(str)
        months=pd.period_range(c.entry_time.min().tz_localize(None).to_period('M'),c.entry_time.max().tz_localize(None).to_period('M'),freq='M').astype(str)
        sequences=[c.loc[c.month==m,'cluster_r'].to_numpy(float) for m in months]
    else:
        # Backward-compatible single sequence. Corrected callers pass frames.
        sequences=[np.asarray(data,dtype=float)]
    m=len(sequences);block=min(3,m);rng=np.random.default_rng(SEED+2);dds=np.empty(n);blocks=int(math.ceil(m/block))
    for j in range(n):
        starts=rng.integers(0,m,size=blocks)
        order=np.concatenate([(np.arange(block)+q)%m for q in starts])[:m]
        rr=np.concatenate([sequences[q] for q in order])
        if len(rr)==0:dds[j]=0.;continue
        eq=np.cumprod(1+risk*rr);peak=np.maximum.accumulate(np.r_[1.,eq])[1:];dds[j]=abs(min(float(np.min(eq/peak-1)),0.))
    return {'samples':n,'block_months':block,'cluster_key':'entry_time','q50_max_drawdown_abs':float(np.quantile(dds,.5)),'q95_max_drawdown_abs':float(np.quantile(dds,.95)),'q99_max_drawdown_abs':float(np.quantile(dds,.99))}


def temporal_null_pvalue(t,n=4000,block_months=3):
    """One-sided moving-block p-value for positive mean trade R."""
    if len(t)<20:return 1.
    z=t.copy();z['entry_time']=pd.to_datetime(z.entry_time,utc=True);z['month']=z.entry_time.dt.tz_localize(None).dt.to_period('M').astype(str)
    months=pd.period_range(z.entry_time.min().tz_localize(None).to_period('M'),z.entry_time.max().tz_localize(None).to_period('M'),freq='M').astype(str)
    g=z.groupby('month').r_net.agg(['sum','count']).reindex(months,fill_value=0.)
    sums=g['sum'].to_numpy(float);cnt=g['count'].to_numpy(float);observed=sums.sum()/max(cnt.sum(),1.)
    centered=sums-observed*cnt;m=len(sums);block=min(block_months,m);blocks=int(math.ceil(m/block));rng=np.random.default_rng(SEED+3)
    exceed=0
    for _ in range(n):
        starts=rng.integers(0,m,size=blocks);pick=np.concatenate([(np.arange(block)+q)%m for q in starts])[:m]
        null_mean=centered[pick].sum()/max(cnt[pick].sum(),1.)
        exceed += null_mean>=observed
    return float((exceed+1)/(n+1))


def holm_bonferroni(rows,alpha=.05,p_key='p_value'):
    """Return rows with strong-family-wise-error adjusted p-values."""
    out=[dict(x) for x in rows];order=sorted(range(len(out)),key=lambda i:(float(out[i][p_key]),str(out[i].get('candidate',''))));running=0.
    m=len(out)
    for rank,i in enumerate(order):
        adjusted=min(1.,(m-rank)*float(out[i][p_key]));running=max(running,adjusted)
        out[i]['holm_adjusted_p']=running;out[i]['passes_holm_fwer']=bool(running<=alpha);out[i]['family_size']=m;out[i]['alpha']=alpha
    return out


def _fold_metrics(t):
    if not len(t) or 'outer_fold' not in t:return pd.DataFrame(columns=['outer_trades','outer_profit_factor','outer_expectancy_r'])
    return pd.DataFrame([{'fold':fold,**{f'outer_{k}':v for k,v in metrics(g).items()}} for fold,g in t.groupby('outer_fold',sort=True)])


def scoped_gate_report(central_t,nested_t,annual_t,block_t,stress_trades,neighbour_trades,criteria,side=None):
    """Apply every locked gate to one direction or to the combined portfolio."""
    def scope(t):
        if not isinstance(t,pd.DataFrame) or side is None or not len(t):return t.copy()
        return t[t.side==side].copy()
    ct,nt,at,bt=(scope(x) for x in (central_t,nested_t,annual_t,block_t))
    stress={k:metrics(scope(v)) for k,v in stress_trades.items()}
    neigh=[]
    for j,(spec,t) in enumerate(neighbour_trades):
        m=metrics(scope(t));neigh.append({'variant':j,'spec':asdict(spec),'passes_basic':bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.10 and m['expectancy_r']>0),**m})
    temporal=bootstrap_month(ct);mc=monte_carlo(ct)
    return gate_report(ct,month_table(ct),quarter_table(ct),nt,at,_fold_metrics(at),bt,_fold_metrics(bt),stress,neigh,temporal,temporal,mc,regime_table(ct),year_table(ct),criteria)


def gate_report(central_t,month,quarter,nested_t,annual_t,annual_folds,block_t,block_folds,stresses,neighbour_rows,iid,blocks,mc,regimes,years,criteria):
    r=criteria['required_all'];cm=metrics(central_t);nm=metrics(nested_t);am=metrics(annual_t);bm=metrics(block_t)
    active=month[month.trades>0];qactive=quarter[quarter.trades>0];aa=annual_folds[annual_folds.outer_trades>0];ba=block_folds[block_folds.outer_trades>0]
    neigh=sum(bool(x['passes_basic']) for x in neighbour_rows)/max(len(neighbour_rows),1)
    total_abs=float(central_t.r_net.abs().sum()) if len(central_t) else 0.;single=float(central_t.r_net.abs().max()/total_abs) if total_abs else math.inf
    profitable=regimes[regimes.expectancy_r>0];possum=float(profitable.return_r.clip(lower=0).sum());largest_reg=float(profitable.return_r.clip(lower=0).max()/possum) if possum>0 else math.inf
    yabs=years.return_r.abs();largest_year=float(yabs.max()/yabs.sum()) if yabs.sum()>0 else math.inf
    cost=[stresses[k] for k in ('FEES_X1_5','SLIPPAGE_X2','FEES_X1_5_SLIPPAGE_X2')]
    checks={
      'central_trades':cm['trades']>=r['central_trades_min'],'central_pf':float(cm['profit_factor'] or 0)>=r['central_profit_factor_min'],'central_exp':cm['expectancy_r']>=r['central_expectancy_r_min'],
      'active_months':len(active)>=r['active_months_min'],'positive_month_ratio':float((active.expectancy_r>0).mean())>=r['positive_active_month_ratio_min'],'positive_quarter_ratio':float((qactive.expectancy_r>0).mean())>=r['positive_rolling_quarter_ratio_min'],'worst_quarter':(float(qactive.expectancy_r.min()) if len(qactive) else -math.inf)>=r['worst_rolling_quarter_expectancy_r_min'],
      'nested_trades':nm['trades']>=r['purged_monthly_outer_trades_min'],'nested_pf':float(nm['profit_factor'] or 0)>=r['purged_monthly_outer_profit_factor_min'],'nested_exp':nm['expectancy_r']>=r['purged_monthly_outer_expectancy_r_min'],
      'annual_folds':len(aa)>=r['expanding_annual_active_folds_min'],'annual_positive_ratio':float((aa.outer_expectancy_r>0).mean())>=r['expanding_annual_positive_ratio_min'],'annual_pf':float(am['profit_factor'] or 0)>=r['expanding_annual_aggregate_profit_factor_min'],'annual_exp':am['expectancy_r']>=r['expanding_annual_aggregate_expectancy_r_min'],
      'blocked_folds':len(ba)>=r['blocked_year_active_folds_min'],'blocked_positive_ratio':float((ba.outer_expectancy_r>0).mean())>=r['blocked_year_positive_ratio_min'],'blocked_pf':float(bm['profit_factor'] or 0)>=r['blocked_year_aggregate_profit_factor_min'],'blocked_exp':bm['expectancy_r']>=r['blocked_year_aggregate_expectancy_r_min'],
      'cost_pf':all(float(x['profit_factor'] or 0)>=r['cost_stress_profit_factor_min'] for x in cost),'cost_exp':all(x['expectancy_r']>=r['cost_stress_expectancy_r_min'] for x in cost),
      'delay1_pf':float(stresses['ENTRY_DELAY_1H']['profit_factor'] or 0)>=r['entry_delay_1h_profit_factor_min'],'delay1_exp':stresses['ENTRY_DELAY_1H']['expectancy_r']>=r['entry_delay_1h_expectancy_r_min'],'delay2_exp':stresses['ENTRY_DELAY_2H']['expectancy_r']>=r['entry_delay_2h_expectancy_r_min'],'gap_exp':stresses['ADVERSE_GAP_10BP']['expectancy_r']>=r['gap_penalty_expectancy_r_min'],
      'neighbours':neigh>=r['parameter_neighbour_basic_pass_fraction_min'],'iid_q10':(iid['q10_mean_r'] or -math.inf)>=r['iid_bootstrap_q10_expectancy_r_min'],'month_boot_prob':blocks['p_mean_positive']>=r['month_block_bootstrap_probability_positive_min'],'month_boot_q10':(blocks['q10_mean_r'] or -math.inf)>=r['month_block_bootstrap_q10_expectancy_r_min'],'mc_dd':(mc['q95_max_drawdown_abs'] or math.inf)<=r['monte_carlo_95pct_max_drawdown_abs_max'],
      'single_trade':single<=r['single_trade_absolute_contribution_max_fraction'],'regime_buckets':len(profitable)>=r['profitable_regime_buckets_min'],'regime_concentration':largest_reg<=r['largest_positive_regime_contribution_max_fraction'],'year_concentration':largest_year<=r['largest_year_absolute_contribution_max_fraction']}
    diag={'central':cm,'nested_monthly':nm,'expanding_annual_aggregate':am,'blocked_year_aggregate':bm,'active_months':len(active),'positive_active_month_ratio':float((active.expectancy_r>0).mean()) if len(active) else 0.,'positive_quarter_ratio':float((qactive.expectancy_r>0).mean()) if len(qactive) else 0.,'worst_quarter_expectancy_r':float(qactive.expectancy_r.min()) if len(qactive) else None,'annual_positive_ratio':float((aa.outer_expectancy_r>0).mean()) if len(aa) else 0.,'blocked_positive_ratio':float((ba.outer_expectancy_r>0).mean()) if len(ba) else 0.,'neighbour_pass_fraction':neigh,'single_trade_fraction':single,'profitable_regime_buckets':len(profitable),'largest_positive_regime_fraction':largest_reg,'largest_year_abs_fraction':largest_year,'failed_checks':[k for k,v in checks.items() if not v]}
    return {'passes_all':all(checks.values()),'checks':checks,'diagnostics':diag}


def main():
    ap=argparse.ArgumentParser();ap.add_argument('corpus',type=Path);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--protocol',type=Path,required=True);args=ap.parse_args()
    args.output.mkdir(parents=True,exist_ok=True);ah=verify_lock(args.protocol/'ROUND23_ARCHITECTURES_LOCKED.json');ch=verify_lock(args.protocol/'ROUND23_CRITERIA_LOCKED.json');criteria=json.loads((args.protocol/'ROUND23_CRITERIA_LOCKED.json').read_text())
    raw=load_ohlcv(args.corpus);f=prepare_features(raw)
    audit={'status':'PASS','rows_1h':len(raw),'start':str(raw.index.min()),'end':str(raw.index.max()),'continuous_1h':True,'nulls_ohlcv':int(raw[['open','high','low','close','volume']].isna().sum().sum()),'holdout_loaded':False,'pre_2021_extension_status':'NOT_AVAILABLE_IN_LOCAL_EXECUTION; official downloader hook included in artifact','four_hour_source':'DERIVED_CAUSALLY_FROM_1H'}
    (args.output/'CORPUS_AUDIT.json').write_text(json.dumps(audit,indent=2))
    reports=[]
    for central in central_specs():
        name=f'{central.architecture}__{central.side}';od=args.output/name;od.mkdir(exist_ok=True)
        variants=[];neigh=[]
        for j,s in enumerate(neighbours(central)):
            t,m=run_backtest(f,s);variants.append((s,t));basic=bool(m['trades']>=80 and float(m['profit_factor'] or 0)>=1.10 and m['expectancy_r']>0)
            neigh.append({'variant':j,'spec':asdict(s),**m,'passes_basic':basic})
        ct=variants[0][1];month=month_table(ct);quarter=quarter_table(ct);regimes=regime_table(ct);years=year_table(ct)
        nt,nf=nested_monthly(variants);at,af=expanding_annual(variants);bt,bf=blocked_year_cv(variants)
        stresses={}
        for row in criteria['stress_matrix']:
            tt,mm=run_backtest(f,central,fee=row['fee'],slip=row['slippage'],entry_delay_bars=row['entry_delay_bars'],extra_entry_penalty=row['extra_entry_penalty']);stresses[row['id']]=mm
        rr=ct.r_net.to_numpy(float) if len(ct) else np.array([]);iid=bootstrap_iid(rr);blocks=bootstrap_month(ct);mc=monte_carlo(ct)
        gate=gate_report(ct,month,quarter,nt,at,af,bt,bf,stresses,neigh,iid,blocks,mc,regimes,years,criteria)
        ct.to_csv(od/'central_trades.csv',index=False);month.to_csv(od/'central_months.csv',index=False);quarter.to_csv(od/'central_rolling_quarters.csv',index=False);regimes.to_csv(od/'central_regimes.csv',index=False);years.to_csv(od/'central_years.csv',index=False)
        nt.to_csv(od/'purged_monthly_outer_trades.csv',index=False);nf.to_json(od/'purged_monthly_folds.json',orient='records',indent=2);at.to_csv(od/'expanding_annual_outer_trades.csv',index=False);af.to_json(od/'expanding_annual_folds.json',orient='records',indent=2);bt.to_csv(od/'blocked_year_outer_trades.csv',index=False);bf.to_json(od/'blocked_year_folds.json',orient='records',indent=2);pd.DataFrame(neigh).to_json(od/'neighbours.json',orient='records',indent=2)
        report={'central_spec':asdict(central),'central':metrics(ct),'purged_monthly_outer':metrics(nt),'expanding_annual_outer':metrics(at),'blocked_year_outer':metrics(bt),'stress':stresses,'iid_bootstrap':iid,'month_block_bootstrap':blocks,'monte_carlo':mc,'gate':gate}
        (od/'REPORT.json').write_text(json.dumps(json_safe(report),indent=2))
        reports.append({'architecture':central.architecture,'side':central.side,'passes_all':gate['passes_all'],'central':metrics(ct),'purged_monthly_outer':metrics(nt),'expanding_annual_outer':metrics(at),'blocked_year_outer':metrics(bt),'failed_checks':gate['diagnostics']['failed_checks'],'report_path':str(od/'REPORT.json'),'minimum_annual_expectancy':float(af[af.outer_trades>0].outer_expectancy_r.min()) if (len(af) and (af.outer_trades>0).any()) else -math.inf,'mc_q95_dd':mc['q95_max_drawdown_abs']})
    admitted=[r for r in reports if r['passes_all']]
    selected=None
    if admitted:
        admitted.sort(key=lambda z:(z['minimum_annual_expectancy'],z['purged_monthly_outer']['expectancy_r'],-z['mc_q95_dd'],z['architecture'],z['side']),reverse=True);selected=admitted[0]
    decision={'research_only':True,'stable':bool(selected is not None),'decision':'HISTORICALLY_VALIDATED_ROUND23' if selected else 'NO_ROUND23_ARCHITECTURE_PASSED_ALL_LOCKED_GATES','selected_candidate':selected,'admitted_count':len(admitted),'round22_holdout_used_for_selection':False,'round22_status':'REJECTED_ARCHIVED_DIAGNOSTIC_ONLY','scalp_engine_touched':False,'android_integration_allowed':False,'android_files_touched':False,'corpus_audit':audit,'protocol_hashes':{'architectures':ah,'criteria':ch},'candidate_reports':reports}
    (args.output/'ROUND23_FINAL_STRICT_DECISION.json').write_text(json.dumps(json_safe(decision),indent=2));pd.DataFrame([{**{k:v for k,v in r.items() if k not in ('central','purged_monthly_outer','expanding_annual_outer','blocked_year_outer')},**{f'central_{k}':v for k,v in r['central'].items()},**{f'nested_{k}':v for k,v in r['purged_monthly_outer'].items()},**{f'annual_{k}':v for k,v in r['expanding_annual_outer'].items()},**{f'blocked_{k}':v for k,v in r['blocked_year_outer'].items()}} for r in reports]).to_csv(args.output/'CANDIDATE_SUMMARY.csv',index=False)
    print(json.dumps(json_safe(decision),indent=2))

if __name__=='__main__':main()
