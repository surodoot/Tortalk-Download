package net.freehaven.tor.control;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
/** Vendor double only: no Tor protocol, sockets, native library or external I/O. */
public final class TorControlConnection {
    public enum Mode { NORMAL, HANG_GETINFO, HANG_DISABLE, HANG_ENABLE, FAIL_DISABLE, HANG_SOCKET, FAIL_SOCKET, HANG_GETCONF }
    public volatile Mode mode = Mode.NORMAL;
    public volatile String listenerOverride;
    public volatile List<ConfigEntry> configurationOverride;
    public final List<String> events = new CopyOnWriteArrayList<>();
    public final AtomicInteger enables = new AtomicInteger(), disables = new AtomicInteger(), socketConfigurations = new AtomicInteger();
    public final CountDownLatch getInfoEntered = new CountDownLatch(1), disableEntered = new CountDownLatch(1),
            enableEntered = new CountDownLatch(1), configureEntered = new CountDownLatch(1), configureRelease = new CountDownLatch(1),
            getConfEntered = new CountDownLatch(1), getConfRelease = new CountDownLatch(1);
    private final CountDownLatch neverReleased = new CountDownLatch(1);
    private String listener, configuredValue;
    public synchronized void setConf(String key, String value) throws IOException {
        events.add("set:" + key + "=" + value);
        if ("SocksPort".equals(key)) {
            if (org.torproject.jni.TorService.authenticatedMarker != 0)
                throw new AssertionError("SOCKS configuration before offline authentication marker");
            if (!"unix:/data/user/0/dev.gyeopdam.embeddedtor.experiment/files/tor-socks/s OnionTrafficOnly".equals(value))
                throw new AssertionError("Unexpected filesystem listener configuration");
            socketConfigurations.incrementAndGet(); configureEntered.countDown();
            if (mode == Mode.HANG_SOCKET) block(configureRelease);
            if (mode == Mode.FAIL_SOCKET) throw new IOException("HOST_INJECTED_SOCKET_FAILURE");
            listener = "\"" + value.substring(0, value.indexOf(' ')) + "\"";
            configuredValue = value;
            return;
        }
        if (!"DisableNetwork".equals(key)) throw new AssertionError("Unexpected control option");
        if ("0".equals(value)) {
            if (listener == null) throw new AssertionError("Network enabled before private Unix listener configured");
            if (!org.torproject.jni.TorService.directoryVerified)
                throw new AssertionError("Network enabled before private directory verified");
            enables.incrementAndGet(); enableEntered.countDown(); if (mode == Mode.HANG_ENABLE) block(neverReleased);
        } else if ("1".equals(value)) {
            disables.incrementAndGet(); disableEntered.countDown(); if (mode == Mode.HANG_DISABLE) block(neverReleased);
            if (mode == Mode.FAIL_DISABLE) throw new IOException("HOST_INJECTED_CONTROL_FAILURE");
        } else throw new AssertionError("Unexpected control value");
    }
    public synchronized List<ConfigEntry> getConf(String key) throws IOException {
        events.add("conf:" + key); getConfEntered.countDown();
        if (!"SocksPort".equals(key)) throw new AssertionError("Unexpected control configuration query");
        if (mode == Mode.HANG_GETCONF) block(getConfRelease);
        return configurationOverride == null ? List.of(new ConfigEntry("SocksPort", configuredValue)) : configurationOverride;
    }
    public synchronized String getInfo(String key) throws IOException {
        events.add("get:" + key);
        getInfoEntered.countDown(); if (mode == Mode.HANG_GETINFO) block(neverReleased);
        switch (key) {
            case "status/bootstrap-phase": return "NOTICE BOOTSTRAP PROGRESS=100 TAG=done";
            case "status/circuit-established": return "1";
            case "net/listeners/socks": return listenerOverride == null ? listener : listenerOverride;
            default: throw new AssertionError("Unexpected control query");
        }
    }
    private void block(CountDownLatch latch) throws IOException {
        try { latch.await(); } catch (InterruptedException interrupted) { throw new IOException("HOST_INTERRUPTED"); }
    }
}
