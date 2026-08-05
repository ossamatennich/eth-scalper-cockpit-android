package com.ethscalper.cockpit;

import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class V23448ScalpActionSummaryAuditTest {
    @Test public void terminalMetricsIncludeProfitFactorExpectancyDrawdownAndFees(){
        ScalpActionSummary s=new ScalpActionSummary();ScalpActionPlan p=plan();
        ScalpActionPlan.Terminal tp=p.observe(2,p.takeProfit,p.takeProfit+.01,true);
        ScalpActionPlan.Terminal sl=p.observe(3,p.stopLoss,p.stopLoss+.01,true);s.terminal(tp);s.terminal(sl);
        Map<String,Object> m=s.snapshot("ACTION_ON",false);double positive=tp.resultR;
        assertEquals(positive,(Double)m.get("positiveR"),1e-12);assertEquals(1d,(Double)m.get("negativeRAbs"),0);
        assertEquals(positive,(Double)m.get("profitFactorR"),1e-12);
        assertEquals((positive-1d)/2d,(Double)m.get("expectancyR"),1e-12);
        assertEquals(1d,(Double)m.get("maximumDrawdownR"),1e-12);
        assertEquals(tp.estimatedFeesUsdt+sl.estimatedFeesUsdt,(Double)m.get("estimatedFeesUsdt"),1e-12);
    }

    @Test public void zeroDenominatorsAreNull(){Map<String,Object> m=new ScalpActionSummary().snapshot("ACTION_ON",false);assertNull(m.get("profitFactorR"));assertNull(m.get("expectancyR"));assertNull(m.get("opportunitiesPerFreshHour"));}

    @Test public void freshTimeCapsLongGapAndSkipsStalePeriod(){
        ScalpActionSummary s=new ScalpActionSummary();s.observeFresh(1000,true);s.observeFresh(3000,true);
        s.observeFresh(13000,true);s.observeFresh(14000,false);s.observeFresh(15000,true);s.observeFresh(16000,true);
        assertEquals(8000L,s.snapshot("ACTION_ON",false).get("freshObservedMs"));
    }

    @Test public void opportunityFrequencyUsesPublicAndVirtualOpenings(){
        ScalpActionSummary s=new ScalpActionSummary();s.observeFresh(1000,true);s.observeFresh(6000,true);
        s.qualified(true);s.qualified(false);assertEquals(1440d,(Double)s.snapshot("ACTION_ON",false).get("opportunitiesPerFreshHour"),0);
    }

    @Test public void resetClearsAuditCountersButNotCallerMode(){
        ScalpActionSummary s=new ScalpActionSummary();s.observeFresh(1000,true);s.observeFresh(2000,true);s.qualified(true);s.terminal(plan().observe(3,plan().stopLoss,plan().stopLoss+.01,true));s.reset();
        Map<String,Object> m=s.snapshot("DIAGNOSTICS_ONLY",false);assertEquals(0L,m.get("freshObservedMs"));assertEquals(0d,(Double)m.get("positiveR"),0);assertEquals("DIAGNOSTICS_ONLY",m.get("mode"));
    }

    private static ScalpActionPlan plan(){return ScalpActionPlan.build(ScalpActionPolicy.RANGE_EXTREME,"e","LONG","RAW",1,1899.99,1900.01,1.2).plan;}
}
