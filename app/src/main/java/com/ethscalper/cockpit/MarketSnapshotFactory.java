package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.List;

/** Causal snapshot construction shared by every registered traded market. */
public final class MarketSnapshotFactory {
    private MarketSnapshotFactory() {}

    public static MarketSnapshot build(MarketRuntime runtime,
                                       SharedReferenceContext btc, long now) {
        List<MarketRuntime.MarketBar> bars=new ArrayList<>(runtime.candles);
        List<MarketRuntime.MarketBar> btcBars=new ArrayList<>(btc.candles);
        double avgRange=averageRange(bars,20),avgVolume=averageVolume(bars,20);
        double amin=runtime.profile.scaledMinimum(runtime.profile.aMinimumReference,
                positive(runtime.last)?runtime.last:runtime.profile.referencePrice);
        double avg=Math.max(amin,avgRange);
        double move1=move(bars,1),move3=move(bars,3),move8=move(bars,8),move15=move(bars,15);
        double high=high(bars,8),low=low(bars,8),lastVolume=bars.isEmpty()?0:bars.get(bars.size()-1).volume;
        double flow15=normalizedFlow(runtime,now,15_000,avgVolume);
        double flow30=normalizedFlow(runtime,now,30_000,avgVolume);
        double flow60=normalizedFlow(runtime,now,60_000,avgVolume);
        double flow120=normalizedFlow(runtime,now,120_000,avgVolume);
        double range=Math.max(0,high-low);
        double rp=range>0&&positive(runtime.last)?(runtime.last-low)/range:.5;
        rp=Math.max(-2,Math.min(3,rp));
        double dHigh=positive(runtime.last)?high-runtime.last:Double.NaN;
        double dLow=positive(runtime.last)?runtime.last-low:Double.NaN;
        double volumeRatio=avgVolume>0?lastVolume/avgVolume:0;
        double previousHigh=highBeforeLast(bars,8),previousLow=lowBeforeLast(bars,8);
        double anti=Math.max(0,Math.abs(move1/avg)-1)
                +Math.max(0,volumeRatio-2)*.35+Math.max(0,Math.abs(flow15-flow60))*.5
                +(range>0?Math.max(0,Math.abs(rp-.5)-.35)*3:0);
        return MarketSnapshot.builder(now).lastSignalAt(runtime.lastP01ConfirmedAt)
                .market(runtime.profile,runtime.last,runtime.bid,runtime.ask)
                .btc(btc.last,btc.bid,btc.ask).candleCounts(bars.size(),btcBars.size())
                .averages(avgRange,avgVolume).movement(move1,move3,move8,high,low)
                .move15(move15).flow(flow60,lastVolume).btcMove5(percentMove(btcBars,5))
                .flowWindows(flow15,flow30,flow60,flow120)
                .btcMoves(percentMove(btcBars,1),percentMove(btcBars,3),
                        percentMove(btcBars,5),percentMove(btcBars,8))
                .professionalFeatures(range,volumeRatio,rp,dHigh,dLow,dHigh,dLow,dHigh,dLow,
                        move1/avg,move3/avg,move8/avg,move1-move3/3.0,
                        move3/3.0-move8/8.0,
                        positive(runtime.last)&&previousHigh>0?runtime.last-previousHigh:0,
                        positive(runtime.last)&&previousLow>0?previousLow-runtime.last:0,anti)
                .build();
    }

    private static double normalizedFlow(MarketRuntime r,long now,long window,double volume) {
        if (!(volume>0)) return 0;
        double sum=0;
        for (MarketRuntime.AggTrade trade:r.aggTrades) if (now-trade.at<=window)
            sum+=(trade.buyerMaker?-trade.quantity:trade.quantity);
        return sum/volume;
    }
    private static double averageRange(List<MarketRuntime.MarketBar> b,int n) {
        int start=Math.max(0,b.size()-n); if (start==b.size()) return 0;
        double sum=0;for(int i=start;i<b.size();i++)sum+=b.get(i).high-b.get(i).low;
        return sum/(b.size()-start);
    }
    private static double averageVolume(List<MarketRuntime.MarketBar> b,int n) {
        int start=Math.max(0,b.size()-n);if(start==b.size())return 0;
        double sum=0;for(int i=start;i<b.size();i++)sum+=b.get(i).volume;
        return sum/(b.size()-start);
    }
    private static double move(List<MarketRuntime.MarketBar>b,int minutes) {
        if(b.size()<=minutes)return 0;return b.get(b.size()-1).close-b.get(b.size()-1-minutes).close;
    }
    private static double high(List<MarketRuntime.MarketBar>b,int n){double v=0;for(int i=Math.max(0,b.size()-n);i<b.size();i++)v=Math.max(v,b.get(i).high);return v;}
    private static double low(List<MarketRuntime.MarketBar>b,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,b.size()-n);i<b.size();i++)v=Math.min(v,b.get(i).low);return Double.isFinite(v)?v:0;}
    private static double highBeforeLast(List<MarketRuntime.MarketBar>b,int n){double v=0;for(int i=Math.max(0,b.size()-n-1);i<Math.max(0,b.size()-1);i++)v=Math.max(v,b.get(i).high);return v;}
    private static double lowBeforeLast(List<MarketRuntime.MarketBar>b,int n){double v=Double.POSITIVE_INFINITY;for(int i=Math.max(0,b.size()-n-1);i<Math.max(0,b.size()-1);i++)v=Math.min(v,b.get(i).low);return Double.isFinite(v)?v:0;}
    private static double percentMove(List<MarketRuntime.MarketBar>b,int n){if(b.size()<=n)return 0;double old=b.get(b.size()-1-n).close;return old>0?(b.get(b.size()-1).close-old)/old:0;}
    private static boolean positive(double v){return Double.isFinite(v)&&v>0;}
}
