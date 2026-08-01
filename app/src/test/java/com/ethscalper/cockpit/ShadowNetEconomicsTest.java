package com.ethscalper.cockpit;

import org.junit.Test;
import static org.junit.Assert.*;

public class ShadowNetEconomicsTest {
    @Test public void ethAndSolUseTheirOwnCostsAndStepsWithoutChangingActiveQuantity(){
        int activeQuantity=3;
        ShadowNetEconomics.Result eth=ShadowNetEconomics.calculate(MarketProfile.eth(),2000,2,5,3,14.55,7);
        assertTrue(eth.valid);assertEquals(3,activeQuantity);
        assertEquals(1.43,eth.estimatedRoundTripCostPerUnit,1e-12);
        ShadowNetEconomics.Result sol=ShadowNetEconomics.calculate(MarketProfile.sol(),75.8,.08,.18,50,14.55,120);
        assertTrue(sol.valid);assertEquals(1,MarketProfile.sol().quantityStep);
        assertNotEquals(eth.estimatedRoundTripCostPerUnit,sol.estimatedRoundTripCostPerUnit,1e-12);
    }
    @Test public void feesCanExposeBudgetOverrunAndMinimumCanBeImpossible(){
        ShadowNetEconomics.Result e=ShadowNetEconomics.calculate(MarketProfile.eth(),2000,2.5,5,4,14.55,7);
        assertTrue(e.activeExceedsBudgetAfterFees);assertEquals(3,e.feeAwareQuantity);
        MarketProfile fake=MarketProfile.builder("X","X","X1").referencePrice(1).priceTick(.01)
                .quantity(2,2,10).researchCandidate(true).adaptivePriceScale(false)
                .detection(.01,.01,.01).stops(.01,1).targets(.02,2).p02Seed(.02,.01)
                .revalidation(.01,.01).lateDistances(.01,.01).costs(1,1).riskBudgets(1,1)
                .qualityBudgets(1,1,1,1,1).staleReasonCode("X_STALE").build();
        assertEquals(0,ShadowNetEconomics.calculate(fake,1,10,20,2,1,10).feeAwareQuantity);
    }
}
