package android.security.keystore;

import java.security.spec.AlgorithmParameterSpec;

/** HOST TEST DOUBLE: records options for the synthetic memory-only key generator to verify. */
public final class KeyGenParameterSpec implements AlgorithmParameterSpec {
    public final String alias;
    public final int purposes;
    public final int bits;
    public final String[] modes;
    public final String[] paddings;
    public final boolean randomized;
    private KeyGenParameterSpec(Builder b) {
        alias = b.alias; purposes = b.purposes; bits = b.bits;
        modes = b.modes.clone(); paddings = b.paddings.clone(); randomized = b.randomized;
    }
    public static final class Builder {
        private final String alias;
        private final int purposes;
        private int bits;
        private String[] modes = new String[0], paddings = new String[0];
        private boolean randomized;
        public Builder(String alias, int purposes) { this.alias = alias; this.purposes = purposes; }
        public Builder setKeySize(int bits) { this.bits = bits; return this; }
        public Builder setBlockModes(String... modes) { this.modes = modes.clone(); return this; }
        public Builder setEncryptionPaddings(String... paddings) { this.paddings = paddings.clone(); return this; }
        public Builder setRandomizedEncryptionRequired(boolean value) { randomized = value; return this; }
        public KeyGenParameterSpec build() { return new KeyGenParameterSpec(this); }
    }
}
