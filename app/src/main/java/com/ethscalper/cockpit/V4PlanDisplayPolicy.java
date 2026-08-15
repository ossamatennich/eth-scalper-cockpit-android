package com.ethscalper.cockpit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** UI-only ordering. It never changes a plan or its trading priority. */
public final class V4PlanDisplayPolicy {
    private V4PlanDisplayPolicy() {}

    public static List<V4Plan> homeOrder(List<V4Plan> plans) {
        ArrayList<V4Plan> ordered = new ArrayList<>(plans);
        ordered.sort(Comparator.comparingInt((V4Plan plan) -> priority(plan.status))
                .thenComparing(Comparator.comparingLong((V4Plan plan) -> plan.createdAt).reversed()));
        return ordered;
    }

    static int priority(V4Plan.Status status) {
        return switch (status) {
            case EXECUTABLE -> 0;
            case LIMIT_ORDER_POSSIBLE -> 1;
            case ORDER_PLACED -> 2;
            case OPEN -> 3;
            case WAITING -> 4;
            case DATA_UNAVAILABLE -> 5;
            default -> 6;
        };
    }
}
