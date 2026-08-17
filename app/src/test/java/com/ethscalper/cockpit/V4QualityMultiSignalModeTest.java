package com.ethscalper.cockpit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class V4QualityMultiSignalModeTest {
    private static final long DAY=86_400_000L;

    private static V4FeatureEngine.Candidate candidate(String asset){
        return new V4FeatureEngine.Candidate(
                V4Plan.Source.CORE,
                asset,
                V4Plan.Side.LONG,
                5,
                0,
                0
        );
    }

    private static V4Plan plan(
            String id,
            String asset,
            V4Plan.Status status,
            long cutoff
    ){
        return new V4Plan(
                id,
                null,
                V4Plan.Source.CORE,
                asset,
                V4Plan.Side.LONG,
                1,
                100,
                110,
                95,
                5,
                1,
                1,
                cutoff+DAY,
                status,
                "",
                5000,
                .01,
                cutoff,
                null
        );
    }

    @Test public void qualityGateUsesPrior90Only(){
        V4FallbackHistory history=
                new V4FallbackHistory(
                        new V4FallbackHistory.MemoryBackend()
                );

        for(int i=1;i<=90;i++){
            history.observe(
                    i*DAY,
                    i,
                    i
            );
        }

        V4FallbackHistory.QualityGate gate=
                history.qualityGate(
                        91*DAY,
                        62,
                        46
                );

        assertTrue(gate.ready);
        assertEquals(90,gate.priorCount);

        /*
         * P67.5 des valeurs 1..90 = 61.075
         * P50 = 45.5
         */
        assertEquals(61.075,gate.scoreThreshold,1e-12);
        assertEquals(45.5,gate.spreadThreshold,1e-12);

        assertTrue(gate.accepted);

        assertFalse(
                history.qualityGate(
                        91*DAY,
                        61,
                        46
                ).accepted
        );

        assertFalse(
                history.qualityGate(
                        91*DAY,
                        62,
                        45
                ).accepted
        );

        /*
         * qualityGate() ne doit pas injecter le jour courant.
         */
        assertEquals(90,history.size());
        assertEquals(0,history.count(91*DAY));
    }

    @Test public void strictModeStillHasOriginalTwoPlanLimit(){
        long cutoff=100*DAY;

        List<V4Plan> active=List.of(
                plan("btc","BTC",V4Plan.Status.OPEN,cutoff),
                plan("eth","ETH",V4Plan.Status.OPEN,cutoff)
        );

        assertFalse(
                V4CreationPolicy.mayCreateFresh(
                        candidate("SOL"),
                        cutoff,
                        active,
                        active
                )
        );
    }

    @Test public void offModeHasNoNumericActivePlanLimit(){
        long cutoff=100*DAY;

        ArrayList<V4Plan> active=new ArrayList<>();

        active.add(plan("btc","BTC",V4Plan.Status.OPEN,cutoff-DAY));
        active.add(plan("eth","ETH",V4Plan.Status.OPEN,cutoff-DAY));
        active.add(plan("sol","SOL",V4Plan.Status.OPEN,cutoff-DAY));
        active.add(plan("bnb","BNB",V4Plan.Status.OPEN,cutoff-DAY));
        active.add(plan("ada","ADA",V4Plan.Status.OPEN,cutoff-DAY));

        assertTrue(
                V4CreationPolicy.mayCreateFreshUncapped(
                        candidate("XRP"),
                        cutoff,
                        active,
                        active
                )
        );
    }

    @Test public void offModeStillPreventsSameSymbolConflict(){
        long cutoff=100*DAY;

        V4Plan active=
                plan(
                        "btc",
                        "BTC",
                        V4Plan.Status.OPEN,
                        cutoff-DAY
                );

        assertFalse(
                V4CreationPolicy.mayCreateFreshUncapped(
                        candidate("BTC"),
                        cutoff,
                        List.of(active),
                        List.of(active)
                )
        );
    }

    @Test public void offModePreventsDuplicateSymbolSameDecisionDay(){
        long cutoff=100*DAY;

        V4Plan prior=
                plan(
                        "xrp",
                        "XRP",
                        V4Plan.Status.EXPIRED,
                        cutoff
                );

        assertFalse(
                V4CreationPolicy.mayCreateFreshUncapped(
                        candidate("XRP"),
                        cutoff,
                        List.of(),
                        List.of(prior)
                )
        );

        assertTrue(
                V4CreationPolicy.mayCreateFreshUncapped(
                        candidate("ADA"),
                        cutoff,
                        List.of(),
                        List.of(prior)
                )
        );
    }

    @Test public void frozenRiskConstantsRemainUnchanged(){
        assertEquals(.0225,V4RiskSizer.CORE_RISK,0);
        assertEquals(.0075,V4RiskSizer.FALLBACK_RISK,0);
        assertEquals(.024,V4RiskSizer.COMBINED_CAP,0);

        assertEquals(
                .675,
                V4FallbackHistory.QUALITY_SCORE_QUANTILE,
                0
        );

        assertEquals(
                .50,
                V4FallbackHistory.QUALITY_SPREAD_QUANTILE,
                0
        );

        assertEquals(
                90,
                V4FallbackHistory.QUALITY_PRIOR_DAYS
        );

        assertEquals(
                45,
                V4FallbackHistory.QUALITY_MIN_PRIOR
        );
    }

    @Test public void runtimeContainsBothStrictAndQualityPaths()
            throws Exception {
        String runtime=source(
                "src/main/java/com/ethscalper/cockpit/"
                +"V4RuntimeCoordinator.java"
        );

        String features=source(
                "src/main/java/com/ethscalper/cockpit/"
                +"V4FeatureEngine.java"
        );

        assertTrue(
                runtime.contains(
                        "V4CreationPolicy.mayCreateFresh("
                )
        );

        assertTrue(
                runtime.contains(
                        "V4CreationPolicy.mayCreateFreshUncapped("
                )
        );

        assertTrue(
                runtime.contains(
                        "qualityPending=engine.selectQualityFallbacks(snapshots)"
                )
        );

        assertTrue(
                features.contains(
                        "selectFallbackCandidates"
                )
        );

        assertTrue(
                runtime.contains(
                        "o.put(\"realTradingAllowed\",false)"
                )
        );
    }

    private static String source(String relative)
            throws Exception {
        Path root=Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path candidate=root.resolve(relative);

        if(!Files.exists(candidate)){
            candidate=root.resolve("app").resolve(relative);
        }

        if(!Files.exists(candidate)&&root.getParent()!=null){
            candidate=root.getParent().resolve("app").resolve(relative);
        }

        return new String(
                Files.readAllBytes(candidate),
                StandardCharsets.UTF_8
        );
    }
}
