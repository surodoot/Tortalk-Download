package dev.gyeopdam.embeddedtor;

/** Pure opt-in policy: changing screens never creates a second native Tor instance. */
final class TorLifecyclePolicy {
    private int foregroundOwners;
    private boolean requested;
    private boolean failed;
    long generation;
    void foreground() { foregroundOwners++; }
    boolean background() {
        if (foregroundOwners > 0) foregroundOwners--;
        if (foregroundOwners == 0 && requested) { requested = false; generation++; return true; }
        return false;
    }
    boolean start() {
        if (failed || foregroundOwners == 0 || requested) return false;
        requested = true; generation++; return true;
    }
    void stop() { requested = false; generation++; }
    void fail() { failed = true; requested = false; generation++; }
    boolean requested() { return requested && foregroundOwners > 0 && !failed; }
    boolean canStart() { return foregroundOwners > 0 && !failed && !requested; }
    boolean failed() { return failed; }
    int foregroundCount() { return foregroundOwners; }
}
