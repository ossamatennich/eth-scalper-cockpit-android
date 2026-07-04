package com.ethscalper.cockpit;

public final class DiagnosticEntry {
    public final long timestamp;
    public final String code;
    public final String message;

    public DiagnosticEntry(long timestamp, String code, String message) {
        this.timestamp = timestamp;
        this.code = code;
        this.message = message;
    }
}
