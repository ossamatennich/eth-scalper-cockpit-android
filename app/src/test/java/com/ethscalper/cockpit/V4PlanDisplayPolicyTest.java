package com.ethscalper.cockpit;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class V4PlanDisplayPolicyTest {
    @Test public void homeKeepsEveryPlanAndRanksActionability() {
        V4Plan waiting = plan("waiting", V4Plan.Status.WAITING, 40);
        V4Plan open = plan("open", V4Plan.Status.OPEN, 50);
        V4Plan limitOld = plan("limit-old", V4Plan.Status.LIMIT_ORDER_POSSIBLE, 10);
        V4Plan executable = plan("exec", V4Plan.Status.EXECUTABLE, 20);
        V4Plan limitNew = plan("limit-new", V4Plan.Status.LIMIT_ORDER_POSSIBLE, 30);
        List<V4Plan> ordered = V4PlanDisplayPolicy.homeOrder(Arrays.asList(
                waiting, open, limitOld, executable, limitNew));
        assertEquals(5, ordered.size());
        assertEquals("exec", ordered.get(0).planId);
        assertEquals("limit-new", ordered.get(1).planId);
        assertEquals("limit-old", ordered.get(2).planId);
        assertEquals("open", ordered.get(3).planId);
        assertEquals("waiting", ordered.get(4).planId);
    }

    @Test public void orderingDoesNotMutateCallerList() {
        List<V4Plan> input = Arrays.asList(plan("wait", V4Plan.Status.WAITING, 1),
                plan("exec", V4Plan.Status.EXECUTABLE, 2));
        V4PlanDisplayPolicy.homeOrder(input);
        assertEquals("wait", input.get(0).planId);
    }

    private static V4Plan plan(String id, V4Plan.Status status, long createdAt) {
        return new V4Plan(id, null, V4Plan.Source.CORE, "BTC", V4Plan.Side.LONG,
                1, 100, 110, 95, 5, createdAt, createdAt,
                createdAt + 100_000L, status, "", 5_000, .01, createdAt, null);
    }
}
