package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Pure per-cycle collector enforcing the global priority across RAW and legacy sources. */
public final class ScalpActionCandidateArbiter {
    private final ArrayList<Candidate> candidates=new ArrayList<>();
    private long cycleAt=Long.MIN_VALUE;

    public synchronized void beginCycle(long at){cycleAt=at;candidates.clear();}

    public synchronized boolean collect(ScalpActionEngine.Result result,MarketSnapshot snapshot,
                                        long observedAt){
        if(result==null||!result.accepted())return false;
        candidates.add(new Candidate(result,snapshot,observedAt));return true;
    }

    public synchronized Resolution resolve(){
        if(candidates.isEmpty())return new Resolution(cycleAt,null,Collections.emptyList());
        ArrayList<Candidate> ordered=new ArrayList<>(candidates);ordered.sort(ORDER);
        Candidate winner=ordered.remove(0);candidates.clear();
        return new Resolution(cycleAt,winner,Collections.unmodifiableList(ordered));
    }

    public synchronized int size(){return candidates.size();}

    private static final Comparator<Candidate> ORDER=Comparator
            .comparingInt((Candidate c)->c.result.route.priority)
            .thenComparingLong(c->c.observedAt)
            .thenComparing(c->c.result.route.routeId);

    public static final class Candidate {
        public final ScalpActionEngine.Result result;public final MarketSnapshot snapshot;
        public final long observedAt;
        Candidate(ScalpActionEngine.Result result,MarketSnapshot snapshot,long observedAt){
            this.result=result;this.snapshot=snapshot;this.observedAt=observedAt;}
    }

    public static final class Resolution {
        public final long cycleAt;public final Candidate winner;public final List<Candidate> notSelected;
        Resolution(long cycleAt,Candidate winner,List<Candidate> notSelected){
            this.cycleAt=cycleAt;this.winner=winner;this.notSelected=notSelected;}
    }
}
