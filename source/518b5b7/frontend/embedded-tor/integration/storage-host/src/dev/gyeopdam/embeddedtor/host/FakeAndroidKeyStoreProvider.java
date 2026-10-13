package dev.gyeopdam.embeddedtor.host;

import android.security.keystore.KeyGenParameterSpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * HOST TEST DOUBLE. The provider's required AndroidKeyStore name routes unchanged production code.
 * Every key is synthetic and held only in this process's map. Exportability/hardware isolation are
 * deliberately NOT modeled. No key-store serialization, import, or real Android operation exists.
 */
public final class FakeAndroidKeyStoreProvider extends Provider {
    private static final long serialVersionUID = 1L;
    private static final Map<String, SecretKey> KEYS = new HashMap<>();
    public static int generations, keyReads, deletions;
    public FakeAndroidKeyStoreProvider() {
        super("AndroidKeyStore", "1.0", "EXPLICIT HOST DOUBLE: synthetic memory-only keys");
        put("KeyStore.AndroidKeyStore", MemoryKeyStore.class.getName());
        put("KeyGenerator.AES", MemoryKeyGenerator.class.getName());
    }
    public static void reset() { KEYS.clear(); generations = 0; keyReads = 0; deletions = 0; }
    public static void loseKeys() { KEYS.clear(); }
    public static int keyCount() { return KEYS.size(); }

    public static final class MemoryKeyGenerator extends KeyGeneratorSpi {
        private KeyGenParameterSpec spec;
        @Override protected void engineInit(SecureRandom random) { throw new AssertionError("Missing Android parameters"); }
        @Override protected void engineInit(int size, SecureRandom random) { throw new AssertionError("Missing Android parameters"); }
        @Override protected void engineInit(AlgorithmParameterSpec parameters, SecureRandom random)
                throws InvalidAlgorithmParameterException {
            if (!(parameters instanceof KeyGenParameterSpec)) throw new InvalidAlgorithmParameterException("SYNTHETIC_PARAMETERS");
            spec = (KeyGenParameterSpec) parameters;
            if (spec.bits != 256 || spec.purposes != 3 || !spec.randomized
                    || !Arrays.equals(spec.modes, new String[] {"GCM"})
                    || !Arrays.equals(spec.paddings, new String[] {"NoPadding"})) {
                throw new AssertionError("Production key-generation policy changed");
            }
        }
        @Override protected SecretKey engineGenerateKey() {
            if (spec == null) throw new AssertionError("Uninitialized key generation");
            byte[] synthetic = new byte[32]; new SecureRandom().nextBytes(synthetic);
            SecretKey key = new SecretKeySpec(synthetic, "AES"); Arrays.fill(synthetic, (byte) 0);
            KEYS.put(spec.alias, key); generations++; return key;
        }
    }

    public static final class MemoryKeyStore extends KeyStoreSpi {
        @Override public Key engineGetKey(String alias, char[] password) { keyReads++; return KEYS.get(alias); }
        @Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
        @Override public Certificate engineGetCertificate(String alias) { return null; }
        @Override public Date engineGetCreationDate(String alias) { return KEYS.containsKey(alias) ? new Date(0) : null; }
        @Override public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) throws KeyStoreException {
            throw new KeyStoreException("SYNTHETIC_IMPORT_FORBIDDEN");
        }
        @Override public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) throws KeyStoreException {
            throw new KeyStoreException("SYNTHETIC_IMPORT_FORBIDDEN");
        }
        @Override public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
            throw new KeyStoreException("SYNTHETIC_IMPORT_FORBIDDEN");
        }
        @Override public void engineDeleteEntry(String alias) { KEYS.remove(alias); deletions++; }
        @Override public Enumeration<String> engineAliases() { return Collections.enumeration(KEYS.keySet()); }
        @Override public boolean engineContainsAlias(String alias) { return KEYS.containsKey(alias); }
        @Override public int engineSize() { return KEYS.size(); }
        @Override public boolean engineIsKeyEntry(String alias) { return KEYS.containsKey(alias); }
        @Override public boolean engineIsCertificateEntry(String alias) { return false; }
        @Override public String engineGetCertificateAlias(Certificate cert) { return null; }
        @Override public void engineStore(OutputStream output, char[] password) throws IOException {
            throw new IOException("SYNTHETIC_KEY_PERSISTENCE_FORBIDDEN");
        }
        @Override public void engineLoad(InputStream input, char[] password) throws IOException {
            if (input != null || password != null) throw new IOException("SYNTHETIC_KEY_IMPORT_FORBIDDEN");
        }
    }
}
