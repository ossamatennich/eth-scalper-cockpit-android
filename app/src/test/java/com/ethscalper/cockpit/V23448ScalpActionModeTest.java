package com.ethscalper.cockpit;
import org.junit.Test;import static org.junit.Assert.*;
public final class V23448ScalpActionModeTest {
 @Test public void defaultConstantIsActionOn(){assertEquals("ACTION_ON",ScalpActionEngine.ACTION_ON);}
 @Test public void diagnosticsModeNeverMarksPublicByItself(){ScalpActionEngine e=new ScalpActionEngine(new ScalpActionMovementRegistry());ScalpActionEngine.Result r=e.observeRaw(sig(),snap(),new ScalpActionContextTracker.Metrics(0,true,0,true,1,true),new ScalpActionEngine.Common("DIAGNOSTICS_ONLY",true,true,true,false,false),1);assertTrue(r.virtualQualified);assertTrue(r.accepted());}
 @Test public void modeIdentifiersAreDedicated(){assertEquals("nmc_scalp_action_preferences",ScalpActionModeStore.PREFERENCES);assertEquals("scalp_action_mode",ScalpActionModeStore.KEY);}
 private static SignalDecision sig(){return SignalDecision.signal(MarketProfile.eth(),"SHORT","RANGE",1,1,1900,1897,1901,3,1,"",false,0,0,0);}private static MarketSnapshot snap(){return MarketSnapshot.builder(1).eth(1900,1900,1900.01).btc(1,1,1).averages(1.2,1).movement(0,0,0,1910,1890).professionalFeatures(20,1,.99,0,0,0,0,0,0,0,0,0,0,0,0,0,0).build();}
}
