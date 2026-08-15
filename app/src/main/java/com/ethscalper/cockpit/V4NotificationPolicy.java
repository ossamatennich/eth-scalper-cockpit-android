package com.ethscalper.cockpit;

/** Pure presentation policy for loud V4 lifecycle notifications. */
public final class V4NotificationPolicy {
    public enum Event { NONE, ACTIONABLE, ENTRY_FILLED, TP, SL }

    public static final class Message {
        public final String title;
        public final String body;
        Message(String title,String body){this.title=title;this.body=body;}
    }

    private V4NotificationPolicy() {}

    public static Event event(V4Plan.Status before,V4Plan.Status after) {
        if(after==null||after==before)return Event.NONE;
        if(after==V4Plan.Status.LIMIT_ORDER_POSSIBLE||after==V4Plan.Status.EXECUTABLE)return Event.ACTIONABLE;
        if(before==V4Plan.Status.ORDER_PLACED&&after==V4Plan.Status.OPEN)return Event.ENTRY_FILLED;
        if(after==V4Plan.Status.CLOSED_TP)return Event.TP;
        if(after==V4Plan.Status.CLOSED_SL)return Event.SL;
        return Event.NONE;
    }

    public static Message message(V4Plan plan,Event event) {
        if(plan==null||event==null||event==Event.NONE)throw new IllegalArgumentException("notification");
        String prefix=plan.symbol+" "+plan.side.name()+" — ";
        return switch(event){
            case ACTIONABLE->new Message(prefix+"Nouveau plan","Qté "+V4PriceDisplay.compact(plan.quantity())
                    +" · Entry "+V4PriceDisplay.compact(plan.entry)+" · TP "+V4PriceDisplay.compact(plan.tp)
                    +" · SL "+V4PriceDisplay.compact(plan.sl));
            case ENTRY_FILLED->new Message(prefix+"Entrée exécutée","Entry "+V4PriceDisplay.compact(plan.entry)
                    +" · trade maintenant EN COURS");
            case TP->new Message(prefix+"TP ATTEINT","TP "+V4PriceDisplay.compact(plan.tp));
            case SL->new Message(prefix+"SL ATTEINT","SL "+V4PriceDisplay.compact(plan.sl));
            case NONE->throw new IllegalArgumentException("event");
        };
    }
}
