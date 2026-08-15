package com.ethscalper.cockpit;

/** Pure last-valid selection policy used by service publication and export timeout recovery. */
public final class StatusPublicationPolicy {
    private StatusPublicationPolicy() {}

    public static String validatedOrNull(String candidate){
        return SafeJsonNormalizer.isValidObject(candidate)?candidate:null;
    }

    public static Selection select(String full,String minimal,String lastValid){
        String value=validatedOrNull(full);
        if(value!=null)return new Selection(value,"FULL");
        value=validatedOrNull(minimal);
        if(value!=null)return new Selection(value,"MINIMAL");
        value=validatedOrNull(lastValid);
        if(value!=null)return new Selection(value,"LAST_VALID");
        throw new IllegalStateException("no valid status available");
    }

    public static final class Selection {
        public final String serialized,mode;
        Selection(String serialized,String mode){this.serialized=serialized;this.mode=mode;}
    }
}
