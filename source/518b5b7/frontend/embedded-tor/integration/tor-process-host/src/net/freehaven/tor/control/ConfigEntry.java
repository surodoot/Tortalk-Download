package net.freehaven.tor.control;
/** Explicit vendor GETCONF value double; no control protocol or socket access. */
public final class ConfigEntry {
    public final String key, value;
    public ConfigEntry(String key, String value) { this.key = key; this.value = value; }
}
