package com.ethscalper.cockpit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Bounded per-symbol shadow state. No member aliases a public runtime field. */
public final class ShadowResearchCoordinator {
    public static final long COOLDOWN_MS=180_000L;
    private static final int MAX_SIGNATURES=160;
    private final Deque<String> signatureOrder=new ArrayDeque<>();
    private final Set<String> openedSignatures=new HashSet<>();
    private ShadowPlanState active;
    private long lastTerminalAt;

    public synchronized boolean canOpen(String signature,long now) {
        return active==null && (lastTerminalAt==0||now-lastTerminalAt>=COOLDOWN_MS)
                && signature!=null&&!openedSignatures.contains(signature);
    }
    public synchronized boolean open(ShadowPlanState plan) {
        if(plan==null||!canOpen(plan.candidateSignature,plan.openedAt))return false;
        active=plan;openedSignatures.add(plan.candidateSignature);
        signatureOrder.addLast(plan.candidateSignature);
        while(signatureOrder.size()>MAX_SIGNATURES){String old=signatureOrder.removeFirst();
            openedSignatures.remove(old);}
        return true;
    }
    public synchronized ShadowPlanState active(){return active;}
    public synchronized ShadowPlanState.Terminal observe(long now,double bid,double ask,
                                                         boolean marketFresh) {
        if(active==null||!marketFresh||!Double.isFinite(bid)||bid<=0
                ||!Double.isFinite(ask)||ask<=0)return null;
        ShadowPlanState.Terminal terminal=active.observe(now,bid,ask);
        if(terminal!=null){active=null;lastTerminalAt=now;}return terminal;
    }
    public synchronized ShadowPlanState reset() {
        ShadowPlanState previous=active;active=null;openedSignatures.clear();signatureOrder.clear();
        lastTerminalAt=0;return previous;
    }
    public synchronized long lastTerminalAt(){return lastTerminalAt;}
    public synchronized int rememberedSignatures(){return openedSignatures.size();}
}
