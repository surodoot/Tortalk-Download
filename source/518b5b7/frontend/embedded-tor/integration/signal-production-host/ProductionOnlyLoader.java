import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

/** Test bootstrap only. Neither this class nor its loader is packaged in the app. */
public final class ProductionOnlyLoader {
    private static final String PRODUCTION = "libsignal_jni_amd64.so";
    private static final String TESTING = "libsignal_jni_testing_amd64.so";
    private static final String PRODUCTION_SHA256 =
        "1e690028660a8c29e07d02837aa7cd495fd201f476a140b645d293d2abaf9072";

    private static final class Rejected extends AssertionError {
        final String code;
        Rejected(String code) { this.code = code; }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new Rejected(code);
    }

    static final class ProductionLoader extends URLClassLoader {
        int testingResourceDenials;
        ProductionLoader(URL[] urls) { super(urls, ClassLoader.getPlatformClassLoader()); }
        private boolean denied(String name) {
            boolean testing = name.contains("signal_jni_testing");
            if (testing) testingResourceDenials++;
            return testing || (name.contains("signal_jni") && !name.equals(PRODUCTION));
        }
        @Override public URL getResource(String name) {
            return denied(name) ? null : super.getResource(name);
        }
        @Override public InputStream getResourceAsStream(String name) {
            return denied(name) ? null : super.getResourceAsStream(name);
        }
        @Override public Enumeration<URL> getResources(String name) throws IOException {
            return denied(name) ? Collections.emptyEnumeration() : super.getResources(name);
        }
        @Override public Stream<URL> resources(String name) {
            return denied(name) ? Stream.empty() : super.resources(name);
        }
        @Override public URL findResource(String name) {
            return denied(name) ? null : super.findResource(name);
        }
        @Override public Enumeration<URL> findResources(String name) throws IOException {
            return denied(name) ? Collections.emptyEnumeration() : super.findResources(name);
        }
        @Override protected String findLibrary(String name) {
            // Native.kt catches the rejected testing fallback and then tries the
            // pinned production resource. Never search an installed Signal library.
            if (name.startsWith("signal_jni")) throw new UnsatisfiedLinkError("HOST_NATIVE_FALLBACK_DENIED");
            return super.findLibrary(name);
        }
    }

    private static void verifyResourcePolicy(ProductionLoader loader) throws Exception {
        require(loader.getResource(TESTING) == null, "TESTING_RESOURCE_VISIBLE");
        require(loader.getResourceAsStream(TESTING) == null, "TESTING_RESOURCE_VISIBLE");
        require(!loader.getResources(TESTING).hasMoreElements(), "TESTING_RESOURCE_VISIBLE");
        try (Stream<URL> resources = loader.resources(TESTING)) {
            require(resources.findAny().isEmpty(), "TESTING_RESOURCE_VISIBLE");
        }
        require(loader.findResource(TESTING) == null, "TESTING_RESOURCE_VISIBLE");
        require(!loader.findResources(TESTING).hasMoreElements(), "TESTING_RESOURCE_VISIBLE");
        Class<?> nativeClass = Class.forName("org.signal.libsignal.internal.Native", false, loader);
        require(nativeClass.getClassLoader() == loader, "CLASSLOADER_MISMATCH");
        require(nativeClass.getResourceAsStream("/" + TESTING) == null, "TESTING_RESOURCE_VISIBLE");
        require(loader.getResource(PRODUCTION) != null, "PRODUCTION_RESOURCE_MISSING");
        require(loader.getResource("libsignal_jni.so") == null, "UNPINNED_RESOURCE_VISIBLE");
        boolean denied = false;
        try { loader.findLibrary("signal_jni_testing"); }
        catch (UnsatisfiedLinkError expected) { denied = true; }
        require(denied, "NATIVE_FALLBACK_ALLOWED");
        loader.testingResourceDenials = 0;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] chunk = new byte[65536];
            for (int n; (n = in.read(chunk)) != -1;) digest.update(chunk, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void verifyMappings(Path ownedTemporaryDirectory) throws Exception {
        Set<Path> production = new HashSet<>();
        // Only in-memory inspection; the raw map (addresses/paths) is never saved.
        for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
            require(!line.contains("signal_jni_testing"), "TESTING_JNI_MAPPED");
            if (!line.contains("signal_jni")) continue;
            String[] fields = line.trim().split("\\s+", 6);
            require(fields.length == 6, "UNEXPECTED_JNI_MAPPING");
            Path path = Path.of(fields[5]);
            require(path.getFileName().toString().equals(PRODUCTION), "UNEXPECTED_JNI_MAPPING");
            require(path.toRealPath().startsWith(ownedTemporaryDirectory.toRealPath()), "UNOWNED_JNI_MAPPING");
            production.add(path);
        }
        require(production.size() == 1, "PRODUCTION_JNI_NOT_MAPPED");
        require(sha256(production.iterator().next()).equals(PRODUCTION_SHA256), "MAPPED_JNI_HASH_MISMATCH");
    }

    public static void main(String[] args) {
        String result = "{\"status\":\"failed\",\"failure_code\":\"HOST_SETUP_FAILED\"}";
        int exit = 1;
        try {
            require(args.length == 6, "ARGUMENTS_INVALID");
            URL[] urls = new URL[5];
            for (int i = 0; i < urls.length; i++) urls[i] = Path.of(args[i]).toUri().toURL();
            try (ProductionLoader loader = new ProductionLoader(urls)) {
                Thread.currentThread().setContextClassLoader(loader);
                verifyResourcePolicy(loader);
                // Initialize only the upstream library/class machinery, never its logger.
                Class.forName("org.signal.libsignal.internal.Native", true, loader);
                verifyMappings(Path.of(System.getProperty("java.io.tmpdir")));
                require(loader.testingResourceDenials >= 2, "TESTING_DENIAL_NOT_EXERCISED");
                Class<?> fixture = loader.loadClass("dev.gyeopdam.embeddedtor.ProductionPrivacyHost");
                require(fixture.getClassLoader() == loader, "CLASSLOADER_MISMATCH");
                int[] counts = (int[]) fixture.getMethod("run").invoke(null);
                require(counts.length == 2 && counts[0] == 8 && counts[1] == 32, "SCENARIO_COUNT_MISMATCH");
                verifyMappings(Path.of(System.getProperty("java.io.tmpdir")));
                result = "{\"status\":\"passed\",\"synthetic_scenario_groups\":8,"
                    + "\"existing_signal_client_tests\":32,\"production_jni_mapped\":true,"
                    + "\"testing_jni_mapped\":false,\"provider_null_before_and_after\":true,"
                    + "\"testing_resource_denial_exercised\":true,\"resource_policy_verified\":true}";
                exit = 0;
            }
        } catch (Rejected rejected) {
            result = "{\"status\":\"failed\",\"failure_code\":\"" + rejected.code + "\"}";
        } catch (Throwable ignored) {
            // Never serialize a throwable, stack trace, key, message body, or raw log.
            result = "{\"status\":\"failed\",\"failure_code\":\"HOST_SCENARIO_FAILED\"}";
        }
        try { Files.writeString(Path.of(args[5]), result + "\n"); }
        catch (Throwable ignored) { exit = 1; }
        System.exit(exit);
    }
}
