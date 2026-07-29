package com.ethscalper.cockpit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Canonical trace algorithm intentionally restricted to v2.33.2.1 legacy APIs. */
final class EthHistoricalTrace {
    static final long SEED=23_321_042L;
    static final int SNAPSHOTS=20_000;
    static final String SOURCE_COMMIT="5e00f3f88bf2da5237ae7f8c0d851aa0fb4fe251";

    private EthHistoricalTrace() {}

    static Map<String,String> generate() {
        Digest engine=new Digest(),metrics=new Digest(),plans=new Digest(),signatures=new Digest();
        Random random=new Random(SEED);SignalEngine signalEngine=new SignalEngine();
        for(int i=0;i<SNAPSHOTS;i++) {
            MarketSnapshot snapshot=snapshot(random,i);SignalDecision decision=signalEngine.evaluate(snapshot);
            addDecision(engine,decision);engine.add(i);
            signatures.add(SignalSafetyPolicies.candidateSignature(decision));
            signatures.add(SignalSafetyPolicies.deterministicSignature(decision,i/60L));
            if(decision.isSignal()) {
                double adverse=random.nextDouble()*Math.max(.01,snapshot.avgRange20);
                NormalizedSignalMetrics.Result m=NormalizedSignalMetrics.calculate(
                        decision.side,decision,snapshot,adverse);addMetrics(metrics,m);
                P01SleeveFilter.Result p01=P01SleeveFilter.evaluate(m,i%90_001L);
                metrics.add(p01.accepted);metrics.add(p01.reasonCode);
                DynamicTradePlan.Result plan=DynamicTradePlan.calculate(decision.side,
                        decision.entry,snapshot.avgRange20,adverse,snapshot.recentHigh,
                        snapshot.recentLow,7);addPlan(plans,plan);
            }
        }
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        out.put("sourceCommit",SOURCE_COMMIT);out.put("seed",Long.toString(SEED));
        out.put("snapshots",Integer.toString(SNAPSHOTS));out.put("signalEngine",engine.hex());
        out.put("normalizedAndP01",metrics.hex());out.put("dynamicTradePlan",plans.hex());
        out.put("signatures",signatures.hex());out.put("boundaries",boundaryDigest());
        out.put("terminalAndRestore",terminalRestoreDigest());
        Digest overall=new Digest();for(Map.Entry<String,String> e:out.entrySet())overall.add(e.getKey()+"="+e.getValue());
        out.put("overall",overall.hex());return out;
    }

    private static String boundaryDigest() {
        Digest d=new Digest();long created=100_000L;
        SignalDecision p01=candidate("LONG");
        for(long age:new long[]{999,1_000,14_999,15_000}) {
            MarketSnapshot s=p01Snapshot(created+age);
            CandidateLifecycle.FillResult early=CandidateLifecycle.processEarlyP01Candidate(
                    p01,s,true,created,created+age,0,0,false,true,true,p01.entry,false);
            d.add(age);d.add(early.confirmed);d.add(early.reasonCode);
            if(early.earlyP01!=null){d.add(early.earlyP01.accepted);d.add(early.earlyP01.mode);d.add(early.earlyP01.reasonCode);}
            P01SleeveFilter.Result normal=P01SleeveFilter.evaluate(
                    NormalizedSignalMetrics.calculate("LONG",p01,s,0),age);
            d.add(normal.accepted);d.add(normal.reasonCode);
        }
        CandidateLifecycle.FillResult quality=CandidateLifecycle.processEarlyP01Candidate(
                p01,p01Snapshot(created+10_000),true,created,created+10_000,0,0,false,
                true,true,p01.entry,false);
        P01EarlyConfirmation.StabilityResult first=P01EarlyConfirmation.advance(
                created+10_000,0,"",quality.earlyP01);
        P01EarlyConfirmation.StabilityResult at999=P01EarlyConfirmation.advance(
                created+10_999,first.qualitySince,first.mode,quality.earlyP01);
        P01EarlyConfirmation.StabilityResult at1000=P01EarlyConfirmation.advance(
                created+11_000,first.qualitySince,first.mode,quality.earlyP01);
        addStability(d,first);addStability(d,at999);addStability(d,at1000);

        SignalDecision p02=p02Candidate();TrendRegime60.Result trend=p02Trend();
        for(long age:new long[]{20_000,20_001,45_000,45_001}) {
            CandidateLifecycle.FillResult result=CandidateLifecycle.processPendingCandidate(
                    p02,p02Snapshot(created+age),true,created,created+age,0,0,false,
                    CandidateLifecycle.SLEEVE_P02,trend);
            d.add(age);d.add(result.confirmed);d.add(result.reasonCode);d.add(result.sleeve);
        }
        d.add(trend.accepted);d.add(trend.reasonCode);d.add(trend.mode);d.add(trend.count);
        d.add(trend.slope);d.add(trend.t60);d.add(trend.lastPointAt);
        ContinuationConfirmation.Result c=ContinuationConfirmation.evaluate("LONG",
                p01Snapshot(created+15_000),true,created,0);
        d.add(c.confirmed);d.add(c.reasonCode);d.add(c.premium15m);d.add(c.move1Aligned);
        d.add(c.move3Aligned);d.add(c.move8Aligned);d.add(c.move15Aligned);d.add(c.flow30Aligned);
        return d.hex();
    }

    private static String terminalRestoreDigest() {
        Digest d=new Digest();SignalDecision signal=candidate("LONG");
        CandidateLifecycle.TerminalResolution tp=CandidateLifecycle.resolveTerminal(
                "TP_TOUCHED",signal,true,500_000,signal.takeProfit);
        CandidateLifecycle.TerminalResolution sl=CandidateLifecycle.resolveTerminal(
                "SL_TOUCHED",signal,true,500_001,signal.stopLoss);
        addTerminal(d,tp);addTerminal(d,sl);
        d.add(TerminalRearmPersistence.allowsNewCandidate(679_999,500_000));
        d.add(TerminalRearmPersistence.remainingMs(679_999,500_000));
        d.add(TerminalRearmPersistence.allowsNewCandidate(680_000,500_000));
        d.add(TerminalRearmPersistence.remainingMs(680_000,500_000));
        String signature=SignalSafetyPolicies.deterministicSignature(signal,1);
        ActivePlanState state=ActivePlanState.builder().status("ACTIVE").side(signal.side)
                .family(signal.family).reasonCode(signal.reasonCode).reasonText(signal.reasonText)
                .score(signal.score).quantity(signal.quantity).prices(signal.entry,
                        signal.takeProfit,signal.stopLoss).risk(signal.targetMove,signal.stopDistance)
                .times(100_000,115_000,115_000).premium15m(true)
                .notification(signature,SignalSafetyPolicies.confirmedNotificationId(signature))
                .lastMarket(100,99.99,100.01,1).lastP01ConfirmedAt(115_000)
                .movement(signal.impulse,signal.resetConfirmed,signal.movementOrigin,
                        signal.movementExtreme,signal.movementDistance).replayRisk("","")
                .p01(.8,1.6,1.3,.2,.2).sizingDiagnostic("{\"finalQuantity\":3}").build();
        ActivePlanState restored=ActivePlanState.fromMap(state.toMap());
        d.add(restored!=null);if(restored!=null){d.add(restored.status);d.add(restored.side);
            d.add(restored.family);d.add(restored.quantity);d.add(restored.entry);
            d.add(restored.takeProfit);d.add(restored.stopLoss);d.add(restored.notificationId);
            d.add(restored.notificationSignature);addDecision(d,restored.toSignalDecision());}
        return d.hex();
    }

    private static void addDecision(Digest d,SignalDecision v){d.add(v.decision);d.add(v.reasonCode);
        d.add(v.reasonText);d.add(v.side);d.add(v.family);d.add(v.score);d.add(v.quantity);
        d.add(v.entry);d.add(v.takeProfit);d.add(v.stopLoss);d.add(v.targetMove);
        d.add(v.stopDistance);d.add(v.impulse);d.add(v.resetConfirmed);d.add(v.movementOrigin);
        d.add(v.movementExtreme);d.add(v.movementDistance);d.add(v.movementConsumed);}
    private static void addMetrics(Digest d,NormalizedSignalMetrics.Result v){d.add(v.valid);d.add(v.direction);
        d.add(v.a);d.add(v.adverseExcursion);d.add(v.e);d.add(v.r);d.add(v.room);d.add(v.m1);
        d.add(v.m3);d.add(v.m8);d.add(v.f30);d.add(v.f60);d.add(v.volumeRatio);d.add(v.directionalEdge);}
    private static void addPlan(Digest d,DynamicTradePlan.Result v){d.add(v.valid);d.add(v.reasonCode);
        d.add(v.a);d.add(v.adverseExcursion60);d.add(v.structuralRoom);d.add(v.stopRequired);
        d.add(v.stopMaximum);d.add(v.targetFloor);d.add(v.targetRaw);d.add(v.targetDistance);
        d.add(v.stopLoss);d.add(v.takeProfit);d.add(v.roundedStopDistance);
        d.add(v.roundedTargetDistance);d.add(v.grossRewardRisk);d.add(v.estimatedRoundTripCostPerEth);
        d.add(v.riskExecutionAllowancePerEth);d.add(v.riskBudgetUsdt);d.add(v.riskPerEth);
        d.add(v.riskQuantity);d.add(v.qualityCap);d.add(v.finalQuantity);d.add(v.theoreticalMaximumLoss);
        d.add(v.priceTick);d.add(v.legacyRiskBudgetUsdt);d.add(v.legacyRiskQuantity);
        d.add(v.baselineFinalQuantity);d.add(v.quantityUpliftApplied);d.add(v.upliftedQuantity);
        d.add(v.upliftedRiskBudgetUsdt);d.add(v.upliftedRiskQuantity);
        d.add(v.theoreticalMaximumLossBeforeUplift);d.add(v.theoreticalMaximumLossAfterUplift);}
    private static void addStability(Digest d,P01EarlyConfirmation.StabilityResult v){d.add(v.confirmed);
        d.add(v.qualitySince);d.add(v.mode);d.add(v.stabilityMs);d.add(v.reasonCode);}
    private static void addTerminal(Digest d,CandidateLifecycle.TerminalResolution v){d.add(v.terminalResolved);
        d.add(v.exitAt);d.add(v.exitPrice);d.add(v.exitReason);d.add(v.executionClassification);
        d.add(v.result.terminalResolved);d.add(v.result.realizedGross);d.add(v.result.realizedFees);
        d.add(v.result.realizedNet);d.add(v.result.latentGross);
        d.add(v.result.latentNet);d.add(v.result.openRiskAgeMs);}

    private static SignalDecision candidate(String side){boolean l="LONG".equals(side);return SignalDecision.signal(side,
            "SCALP_CONTINUATION",96,3,100,l?102.80:97.20,l?98.65:101.35,2.80,1.35,
            "ACTIVE",true,98,101,3);}
    private static MarketSnapshot p01Snapshot(long now){return MarketSnapshot.builder(now).eth(100,99.99,100)
            .btc(60_000,59_999,60_001).candleCounts(60,60).averages(1,100)
            .movement(.8,1.6,1.3,103,97).move15(.2).flow(.2,120)
            .flowWindows(.2,.2,.2,.2).professionalFeatures(6,1.2,.5,3,3,3,3,3,3,.8,1.6,1.3,0,0,1,1,0).build();}
    private static SignalDecision p02Candidate(){return SignalDecision.signal("LONG","SCALP_CONTINUATION",90,3,
            100,102.8,98.65,2.8,1.35,"P02",true,99,100,1);}
    private static MarketSnapshot p02Snapshot(long now){return MarketSnapshot.builder(now).eth(100,99.99,100)
            .btc(60_000,59_999,60_001).candleCounts(60,60).averages(1,100)
            .movement(.6,1.3,1,101.5,98).move15(.2).flow(.5,50)
            .flowWindows(.2,.2,.5,.5).professionalFeatures(3.5,.5,.7,1.5,2,1.5,2,1.5,2,.6,1.3,1,0,0,.5,.5,0).build();}
    private static TrendRegime60.Result p02Trend(){long first=1_000;List<TrendRegime60.Point> points=new ArrayList<>();
        for(int i=0;i<60;i++)points.add(new TrendRegime60.Point((first+i)*60_000L+50_000,100+i*.05));
        NormalizedSignalMetrics.Result metrics=NormalizedSignalMetrics.calculate("LONG",p02Candidate(),p02Snapshot((first+59)*60_000L+50_000),.05);
        return TrendRegime60.evaluate("LONG",1,metrics,points,(first+59)*60_000L+50_000);}
    private static MarketSnapshot snapshot(Random r,int i){double last=1500+r.nextDouble()*1500;
        double spread=r.nextDouble()*.70,bid=last-spread*.5,ask=last+spread*.5;
        double avg=.20+r.nextDouble()*2.5,m1=(r.nextDouble()-.5)*avg*5;
        double m3=(r.nextDouble()-.5)*avg*9,m8=(r.nextDouble()-.5)*avg*15;
        double lowRoom=r.nextDouble()*8,highRoom=r.nextDouble()*8,range=lowRoom+highRoom;
        double rp=range>0?lowRoom/range:.5,vr=r.nextDouble()*4;
        return MarketSnapshot.builder(1_700_000_000_000L+i*1000L).eth(last,bid,ask)
                .btc(60_000,59_999.5,60_000.5).candleCounts(30+r.nextInt(151),10+r.nextInt(171))
                .averages(avg,100+r.nextDouble()*1000).movement(m1,m3,m8,last+highRoom,last-lowRoom)
                .move15((r.nextDouble()-.5)*avg*20).flow((r.nextDouble()-.5),50+r.nextDouble()*1000)
                .flowWindows((r.nextDouble()-.5),(r.nextDouble()-.5),(r.nextDouble()-.5),(r.nextDouble()-.5))
                .professionalFeatures(range,vr,rp,highRoom,lowRoom,highRoom,lowRoom,highRoom*.1,
                        lowRoom*.1,m1/avg,m3/avg,m8/avg,m1/avg-m3/avg,m3/avg-m8/avg,
                        highRoom,lowRoom,r.nextDouble()).btcMoves((r.nextDouble()-.5)*.006,
                        (r.nextDouble()-.5)*.006,(r.nextDouble()-.5)*.006,(r.nextDouble()-.5)*.006).build();}

    private static final class Digest {private final MessageDigest digest;
        Digest(){try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
        void add(String v){byte[] b=(v==null?"<null>":v).getBytes(StandardCharsets.UTF_8);digest.update(b);digest.update((byte)'\n');}
        void add(boolean v){add(Boolean.toString(v));}void add(int v){add(Integer.toString(v));}
        void add(long v){add(Long.toString(v));}void add(double v){add(Long.toHexString(Double.doubleToLongBits(v)));}
        String hex(){StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format("%02x",b&255));return s.toString();}}
}
