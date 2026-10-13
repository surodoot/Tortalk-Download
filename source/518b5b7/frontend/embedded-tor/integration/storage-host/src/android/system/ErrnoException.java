package android.system;

/** HOST TEST DOUBLE: deliberately fixed, non-secret diagnostic. */
public final class ErrnoException extends Exception {
    private static final long serialVersionUID = 1L;
    public final int errno;
    public ErrnoException(String operation, int errno) { super("SYNTHETIC_" + operation); this.errno = errno; }
}
