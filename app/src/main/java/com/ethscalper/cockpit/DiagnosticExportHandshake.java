package com.ethscalper.cockpit;

/** Pure request/ack matcher. A stale service broadcast can never start a newer export. */
public final class DiagnosticExportHandshake {
    private final String requestId;private final long requestedAt,deadlineAt;
    private boolean completed;
    public DiagnosticExportHandshake(String requestId,long requestedAt,long timeoutMs){
        if(requestId==null||requestId.isEmpty())throw new IllegalArgumentException("requestId");
        this.requestId=requestId;this.requestedAt=requestedAt;this.deadlineAt=requestedAt+Math.max(1,timeoutMs);
    }
    public synchronized boolean acknowledge(String value,boolean flushCompleted){
        if(completed||!flushCompleted||!requestId.equals(value))return false;completed=true;return true;
    }
    public synchronized boolean timeout(long now){if(completed||now<deadlineAt)return false;completed=true;return true;}
    public synchronized boolean isCompleted(){return completed;}
    public String requestId(){return requestId;}public long requestedAt(){return requestedAt;}
}
