package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class V4CreationPolicyTest {
    private V4Plan plan(String id,String parent,String asset,V4Plan.Side side,V4Plan.Status status,long cutoff){V4Plan p=new V4Plan(id,parent,V4Plan.Source.CORE,asset,side,1,100,110,95,5,1,1,1000,status,"",5000,.01,cutoff,null);
        if(status==V4Plan.Status.CLOSED_OTHER)p.closeReason="Clôture avant reset";return p;}
    private V4FeatureEngine.Candidate candidate(String asset,V4Plan.Side side){return new V4FeatureEngine.Candidate(V4Plan.Source.CORE,asset,side,5,0,0);}
    @Test public void freshAndDifferentSymbolContinuationMayCoexistUpToTwo(){V4Plan parent=plan("parent",null,"BTC",V4Plan.Side.LONG,V4Plan.Status.CLOSED_OTHER,1);
        V4Plan freshPlan=plan("fresh",null,"ETH",V4Plan.Side.SHORT,V4Plan.Status.EXECUTABLE,2);List<V4Plan> active=new ArrayList<>(List.of(freshPlan));
        assertTrue(V4CreationPolicy.mayCreateContinuation(candidate("BTC",V4Plan.Side.LONG),parent,active,List.of(parent,freshPlan),candidate("ETH",V4Plan.Side.SHORT)));
        active.add(plan("other",null,"SOL",V4Plan.Side.LONG,V4Plan.Status.OPEN,2));assertFalse(V4CreationPolicy.mayCreateContinuation(candidate("BTC",V4Plan.Side.LONG),parent,active,List.of(parent),null));}
    @Test public void freshSameSymbolWinsAndNoThirdContinuation(){V4Plan parent=plan("parent",null,"BTC",V4Plan.Side.LONG,V4Plan.Status.CLOSED_OTHER,1);
        assertFalse(V4CreationPolicy.mayCreateContinuation(candidate("BTC",V4Plan.Side.LONG),parent,List.of(),List.of(parent),candidate("BTC",V4Plan.Side.SHORT)));
        V4Plan child=plan("child","parent","BTC",V4Plan.Side.LONG,V4Plan.Status.CLOSED_OTHER,2);
        assertFalse(V4CreationPolicy.mayCreateContinuation(candidate("BTC",V4Plan.Side.LONG),parent,List.of(),List.of(parent,child),null));
        assertFalse(V4CreationPolicy.mayCreateContinuation(candidate("BTC",V4Plan.Side.LONG),child,List.of(),List.of(child),null));}
    @Test public void oneFreshParentPerDecisionDayAndNoSameSymbolContradiction(){long day=10*86_400_000L;V4Plan prior=plan("prior",null,"ETH",V4Plan.Side.LONG,V4Plan.Status.EXPIRED,day);
        assertFalse(V4CreationPolicy.mayCreateFresh(candidate("SOL",V4Plan.Side.LONG),day+100,List.of(),List.of(prior)));
        assertFalse(V4CreationPolicy.mayCreateFresh(candidate("BTC",V4Plan.Side.SHORT),day+86_400_000L,List.of(plan("open",null,"BTC",V4Plan.Side.LONG,V4Plan.Status.OPEN,day)),List.of()));}
    @Test public void riskCapRejectionIsPersistableAndNeverActionable(){V4Plan p=plan("new",null,"ETH",V4Plan.Side.LONG,V4Plan.Status.WAITING,2);V4CreationPolicy.rejectForRiskCap(p,500);
        assertTrue(p.terminal());assertEquals(V4Plan.Status.CLOSED_OTHER,p.status);assertEquals("RISK_CAP_REACHED",p.statusReason);assertEquals(500,p.closedAt);}
}
