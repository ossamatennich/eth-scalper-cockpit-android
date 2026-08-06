package com.ethscalper.cockpit;

final class CvCoreTestFixtures {
    private CvCoreTestFixtures(){}
    static CvCoreContextTracker.Metrics metrics(double ethRet,double solRet,double ethEff,double solEff,double btc8,double btc3){
        return new CvCoreContextTracker.Metrics(ethRet,true,solRet,true,ethEff,true,solEff,true,btc8,btc3,
                1,1,1,0,0,0,61,61,31,1,1,1);}
    static SignalDecision signal(String side,String family){return SignalDecision.signal(MarketProfile.eth(),side,family,
            80,1,1900,1901,1899,1,1,"TEST",false,1900,1901,1);}
    static MarketSnapshot snapshot(double bid,double ask,double a,double btc8,double btc3){return MarketSnapshot.builder(1)
            .eth((bid+ask)/2,bid,ask).btc(60_000,59_999,60_001).averages(a,1)
            .movement(0,btc3,btc8,1910,1890).professionalFeatures(20,1,.5,1,1,1,1,0,0,0,0,0,0,0,0,0,0).build();}
    static CvCoreEngine.Common common(){return new CvCoreEngine.Common(true,true,true,false,false,0,0,0);}
    static CvCorePlan plan(CvCorePolicy.Route route,String side){CvCorePlan.BuildResult r=CvCorePlan.build(route,"episode",side,"RAW",1,1900,1900.01,1.2);if(!r.accepted())throw new AssertionError(r.reasonCode);return r.plan;}
}
