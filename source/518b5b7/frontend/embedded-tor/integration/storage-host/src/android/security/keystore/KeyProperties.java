package android.security.keystore;

/** HOST TEST DOUBLE: Android constant names, not a security provider. */
public final class KeyProperties {
    public static final String KEY_ALGORITHM_AES = "AES";
    public static final String BLOCK_MODE_GCM = "GCM";
    public static final String ENCRYPTION_PADDING_NONE = "NoPadding";
    public static final int PURPOSE_ENCRYPT = 1;
    public static final int PURPOSE_DECRYPT = 2;
    private KeyProperties() { }
}
