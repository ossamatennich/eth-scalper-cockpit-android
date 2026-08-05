package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class V23448ScalpActionPublicationGuardTest {
    @Test public void validPublicationPasses(){assertTrue(validate("ACTION_ON",true,true,true,false,false,1000,1900,1900.01).published);}
    @Test public void diagnosticsModeRejectsPrecisely(){assertReason("SCALP_ACTION_MODE_DIAGNOSTICS_ONLY",validate("DIAGNOSTICS_ONLY",true,true,true,false,false,1000,1900,1900.01));}
    @Test public void staleFeedsHaveDistinctReasons(){assertReason("SCALP_ACTION_ETH_FEED_STALE",validate("ACTION_ON",false,true,true,false,false,1000,1900,1900.01));assertReason("SCALP_ACTION_BTC_FEED_STALE",validate("ACTION_ON",true,false,true,false,false,1000,1900,1900.01));assertReason("SCALP_ACTION_SOL_FEED_STALE",validate("ACTION_ON",true,true,false,false,false,1000,1900,1900.01));}
    @Test public void publicOrActionPlanBlocks(){assertReason("SCALP_ACTION_PUBLIC_PLAN_ACTIVE",validate("ACTION_ON",true,true,true,true,false,1000,1900,1900.01));assertReason("SCALP_ACTION_PUBLIC_PLAN_ACTIVE",validate("ACTION_ON",true,true,true,false,true,1000,1900,1900.01));}
    @Test public void expiredWindowRejects(){assertReason("SCALP_ACTION_ENTRY_WINDOW_EXPIRED",validate("ACTION_ON",true,true,true,false,false,6002,1900,1900.01));}
    @Test public void incoherentCurrentQuoteRejectsWithoutMovingPlan(){ScalpActionPlan p=plan();double entry=p.entry;ScalpActionPublicationGuard.PublicationResult r=ScalpActionPublicationGuard.validate(p,new ScalpActionPublicationGuard.Context("ACTION_ON",1000,true,true,true,false,false,1901,1900));assertReason("SCALP_ACTION_INVALID_QUOTE",r);assertEquals(entry,p.entry,0);}
    @Test public void quantityZeroReasonIsPrecise(){assertEquals("SCALP_ACTION_QUANTITY_ZERO",ScalpActionPublicationGuard.validateEconomics(0,1,.4,1));}
    @Test public void nonPositiveTargetReasonIsPrecise(){assertEquals("SCALP_ACTION_TARGET_NOT_NET_POSITIVE",ScalpActionPublicationGuard.validateEconomics(1,0,.4,1));}
    @Test public void lowRrReasonIsPrecise(){assertEquals("SCALP_ACTION_NET_RR_TOO_LOW",ScalpActionPublicationGuard.validateEconomics(1,1,.399999,1));}
    @Test public void feeInclusiveBudgetReasonIsPrecise(){assertEquals("SCALP_ACTION_RISK_BUDGET_EXCEEDED",ScalpActionPublicationGuard.validateEconomics(1,1,.4,14.5500001));}

    private static ScalpActionPublicationGuard.PublicationResult validate(String mode,boolean eth,boolean btc,boolean sol,boolean pub,boolean action,long now,double bid,double ask){return ScalpActionPublicationGuard.validate(plan(),new ScalpActionPublicationGuard.Context(mode,now,eth,btc,sol,pub,action,bid,ask));}
    private static ScalpActionPlan plan(){return ScalpActionPlan.build(ScalpActionPolicy.RANGE_EXTREME,"e","SHORT","RAW",1000,1900,1900.01,1.2).plan;}
    private static void assertReason(String reason,ScalpActionPublicationGuard.PublicationResult result){assertFalse(result.published);assertEquals(reason,result.reasonCode);}
}
