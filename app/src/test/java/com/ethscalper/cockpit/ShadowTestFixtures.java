package com.ethscalper.cockpit;

final class ShadowTestFixtures {
    private ShadowTestFixtures() {}
    static SignalDecision candidate(MarketProfile p,String side,int score){
        int d="LONG".equals(side)?1:-1;double entry=MarketProfile.ETH_SYMBOL.equals(p.symbol)?100:75.8;
        return SignalDecision.signal(p,side,"CONTINUATION",score,3,entry,entry+d*3,
                entry-d,3,1,"P01",true,entry-d,entry+d,2);
    }
    static MarketSnapshot snapshot(MarketProfile p,String side,long now,double a,double m1,
            double m3,double m8,double f30,double f60,double volumeRatio,double edge,double room){
        int d="LONG".equals(side)?1:-1;double entry=MarketProfile.ETH_SYMBOL.equals(p.symbol)?100:75.8;
        double high=d>0?entry+room*a:entry+a;double low=d>0?entry-a:entry-room*a;
        double rangePosition=d>0?edge:1-edge;
        return MarketSnapshot.builder(now).market(p,entry,entry-.01,entry+.01)
                .btc(60_000,59_999,60_001).averages(a,100)
                .movement(d*m1*a,d*m3*a,d*m8*a,high,low).move15(d*a)
                .flow(0,volumeRatio*100).professionalFeatures(high-low,volumeRatio,rangePosition,
                        Math.max(0,high-entry),Math.max(0,entry-low),high-entry,entry-low,0,0,
                        0,0,0,0,0,0,0,0).flowWindows(d*f30,d*f30,d*f60,d*f60)
                .btcMoves(0,0,0,0).candleCounts(180,180).build();
    }
    static NormalizedSignalMetrics.Result metrics(MarketProfile p,String side,long now,double a,
            double m1,double m3,double m8,double f30,double f60,double vr,double edge,double room){
        SignalDecision c=candidate(p,side,95);
        return NormalizedSignalMetrics.calculate(p,side,c,
                snapshot(p,side,now,a,m1,m3,m8,f30,f60,vr,edge,room),0);
    }
}
