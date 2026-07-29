#!/usr/bin/env python3
"""Offline parity audit for the immutable validated v2.33.1 ETH plan fixture."""
from __future__ import annotations
import csv, hashlib, math, pathlib, sys

ROOT=pathlib.Path(__file__).resolve().parents[1]
FIXTURE=ROOT/'tools'/'fixtures'/'eth_v2331_validated_plans.csv'
EXPECTED_SHA='4b49d0df47f17783a62c9ef1e7eeedd8f40e61438832f2d86e85a498d80fe7bd'

def main() -> int:
    raw=FIXTURE.read_bytes(); digest=hashlib.sha256(raw).hexdigest()
    with FIXTURE.open(encoding='utf-8',newline='') as source: rows=list(csv.DictReader(source))
    assert len(rows)==16, len(rows)
    assert sum(r['sleeve']=='P01' for r in rows)==7
    assert sum(r['sleeve']=='P02' for r in rows)==9
    assert sum(r['mode']=='TREND' for r in rows)==6
    assert sum(r['mode']=='REVERSAL' for r in rows)==3
    assert sum(r['side']=='LONG' for r in rows)==6
    assert sum(r['side']=='SHORT' for r in rows)==10
    assert all(r['outcome']=='TP' for r in rows)
    for r in rows:
        entry=float(r['entry']); stop=float(r['stopDistance']); target=float(r['targetDistance'])
        sl=float(r['stopLoss']); tp=float(r['takeProfit']); qty=int(r['quantity'])
        assert qty in (2,3)
        if r['side']=='LONG':
            assert math.isclose(entry-sl,stop,abs_tol=.011)
            assert math.isclose(tp-entry,target,abs_tol=.011)
        else:
            assert math.isclose(sl-entry,stop,abs_tol=.011)
            assert math.isclose(entry-tp,target,abs_tol=.011)
        assert int(r['confirmationAt'])-int(r['createdAt'])>=15_000
        assert int(r['exitAt'])>=int(r['confirmationAt'])
    # The expected digest is printed on first creation and then frozen in the report/tests.
    if EXPECTED_SHA!='TO_BE_FROZEN' and digest!=EXPECTED_SHA:
        raise AssertionError(f'fixture SHA mismatch: {digest}')
    print(f'PASS ETH v2.33.1 replay: plans=16 P01=7 P02=9 TP=16 SL=0 sha256={digest}')
    return 0

if __name__=='__main__':
    sys.exit(main())
