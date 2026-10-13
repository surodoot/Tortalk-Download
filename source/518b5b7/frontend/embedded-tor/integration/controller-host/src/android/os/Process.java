package android.os;
/** Synthetic identities only; no host process identity is exposed or used. */
public final class Process {
 public static final int UID=10001, MAIN_PID=22001, TOR_PID=22002;
 public static int myUid(){return UID;}
 public static int myPid(){return MAIN_PID;}
}
