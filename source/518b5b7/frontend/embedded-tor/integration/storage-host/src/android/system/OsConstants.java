package android.system;

/** HOST TEST DOUBLE: symbolic values for asserting the public API26 contract, not Linux ABI values. */
public final class OsConstants {
    public static final int O_RDONLY = 0;
    public static final int O_NOFOLLOW = 1;
    public static final int EIO = 5;
    public static final int HOST_DIRECTORY = 7;
    public static final int HOST_REGULAR_FILE = 8;
    public static boolean S_ISDIR(int mode) { return mode == HOST_DIRECTORY; }
    private OsConstants() { }
}
