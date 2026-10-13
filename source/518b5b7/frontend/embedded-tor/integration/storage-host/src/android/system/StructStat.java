package android.system;

/** HOST TEST DOUBLE: the only stat field accessed by production storage. */
public final class StructStat {
    public final int st_mode;
    public StructStat(int mode) { st_mode = mode; }
}
