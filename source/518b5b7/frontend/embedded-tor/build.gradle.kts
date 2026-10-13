import java.security.MessageDigest

// Independent toolchain: current official Tor AAR needs SDK 37.1 and Java 24 class support.
plugins { id("com.android.application") version "9.4.1" }

// Reviewed CI provenance: scripts/provenance/signal-strip-0.105.0-ndk27.3.13750724.json.
// Fail during configuration, before AGP can resolve/install a missing NDK.
// Smaller JNI packaging is supported only with this existing Linux toolchain.
val signalNdkDir = providers.environmentVariable("ANDROID_HOME").orNull
    ?.let { file("$it/ndk/27.3.13750724") }
    ?: error("ANDROID_HOME must identify the existing reviewed Android SDK")
val signalNdkProperties = file("$signalNdkDir/source.properties")
val signalStripTool = file("$signalNdkDir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip")
check(signalNdkProperties.isFile && signalStripTool.isFile && signalStripTool.canExecute()) {
    "Reviewed preinstalled NDK 27.3.13750724 is required; automatic installation is not permitted"
}
check(signalStripTool.canonicalFile.toPath().startsWith(signalNdkDir.canonicalFile.toPath())) {
    "Strip tool resolves outside the reviewed NDK"
}
mapOf(
    signalNdkProperties to "fb5eec1ca5dac09442109cf79e9ce49faecd8b312dbc997e88903a394e14994a",
    signalStripTool to "b811889ca41db2f6242a6498833e3e1705faf099bb382313f8feb2b070c79e9d"
).forEach { (input, expected) ->
    val actual = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
        .joinToString("") { "%02x".format(it) }
    check(actual == expected) { "Signal strip toolchain bytes changed; review provenance before building" }
}
android {
    namespace = "dev.gyeopdam.embeddedtor"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.3.13750724"
    ndkPath = signalNdkDir.absolutePath
    defaultConfig {
        applicationId = "dev.gyeopdam.embeddedtor.experiment"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5-pilot"
        testInstrumentationRunner = "dev.gyeopdam.embeddedtor.TorTalkRuntimeInstrumentation"
    }
    buildTypes {
        debug { signingConfig = null; isDebuggable = true }
        release { signingConfig = null }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        jniLibs.keepDebugSymbols += "**/libtor.so"
        // AGP invokes the verified llvm-strip --strip-unneeded. The final APK
        // verifier requires all four exact reviewed stripped Signal hashes.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
        resources {
            merges += "META-INF/io.netty.versions.properties"
            excludes += "META-INF/INDEX.LIST"
            excludes += setOf("libsignal_jni_*.so", "libsignal_jni_*.dylib", "signal_jni_*.dll")
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
        informational += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }
}
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
dependencies {
    implementation("org.signal:libsignal-android:0.105.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("info.guardianproject:tor-android:0.4.9.13.2")
    implementation("info.guardianproject:jtorctl:0.4.5.7")
    implementation("io.netty:netty-handler-proxy:4.1.139.Final")
    implementation("io.netty:netty-codec-http:4.1.139.Final")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
    // Host unit tests need executable JSON, not Android SDK stub implementations.
    testImplementation("org.json:json:20250517")
}

// Verify the actual resolved binaries consumed by this build, not a separate download.
// Pinning official bytes is not a reproducible-build or independent signature proof.
val verifyOfficialTorArtifacts = tasks.register("verifyOfficialTorArtifacts") {
    doLast {
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val pins = mapOf(
            "tor-android" to "40be745450a8ecfaac8558950bf046f596d7ef6e88107f69334a8c4b6e7d14be",
            "jtorctl" to "51bbed22de9fe0028095223ceeb4694d8731eda2fc01edee07b8c0c8a46681c3"
        )
        pins.forEach { (name, expected) ->
            val artifact = artifacts.single { it.moduleVersion.id.group == "info.guardianproject" && it.name == name }
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Official $name artifact bytes changed; review provenance before building" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyOfficialTorArtifacts) }

val verifyProbeArtifacts = tasks.register("verifyProbeArtifacts") {
    doLast {
        val pins = file("scripts/netty-artifacts.sha256").readLines().filter { it.isNotBlank() }
            .associate { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts[1].substringAfterLast('/') to parts[0]
            }
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "io.netty" }
        val resolvedNames = artifacts.map { it.file.name }.toSet()
        check(resolvedNames == pins.keys) {
            "Probe Netty dependency set changed. " +
                "Missing pinned artifacts: ${(pins.keys - resolvedNames).sorted()}; " +
                "unexpected resolved artifacts: ${(resolvedNames - pins.keys).sorted()}; " +
                "resolved artifacts: ${resolvedNames.sorted()}"
        }
        artifacts.forEach { artifact ->
            val actual = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == pins[artifact.file.name]) { "Probe artifact bytes changed: ${artifact.name}" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyProbeArtifacts) }

// Official Signal Android JNI plus API jar. Native testing and desktop libraries are excluded above.
val verifySignalArtifacts = tasks.register("verifySignalArtifacts") {
    doLast {
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val pins = mapOf(
            "libsignal-android" to "a3977f6563d82334f6a67ca9eaf42a7e2192bb5d06b539fe089dfc9a9502c6f8",
            "libsignal-client" to "520a16906c45e1629ed05c98b12556322ccc489a82016bba921954e312c882c4"
        )
        pins.forEach { (name, expected) ->
            val artifact = artifacts.single { it.moduleVersion.id.group == "org.signal" && it.name == name }
            val actual = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == expected) { "Official $name artifact bytes changed; review provenance before building" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifySignalArtifacts) }

// Pure QR core only: no Android/camera wrapper, javase module, or native code.
val verifyContactQrArtifact = tasks.register("verifyContactQrArtifact") {
    doLast {
        val artifacts = configurations.getByName("debugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .filter { it.moduleVersion.id.group == "com.google.zxing" }
        check(artifacts.size == 1) { "Unexpected ZXing runtime artifact inventory" }
        val core = artifacts.single()
        check(core.name == "core" && core.moduleVersion.id.version == "3.5.4") {
            "Only the reviewed ZXing core is permitted"
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(core.file.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == "71de5d89341b5fcf5dd89da7f44e84d825d0e084cdf3ec77c9abe26b0f0ceb13") {
            "Contact QR artifact bytes changed; review provenance before building"
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyContactQrArtifact) }

// Pin the exact test-only provider used by NativeResponseRules and other host JSON tests.
val verifyUnitTestJsonArtifact = tasks.register("verifyUnitTestJsonArtifact") {
    doLast {
        val artifacts = configurations.getByName("debugUnitTestRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
        val json = artifacts.single { it.moduleVersion.id.group == "org.json" && it.name == "json" }
        check(json.moduleVersion.id.version == "20250517") { "Unexpected JSON host-test provider version" }
        val actual = MessageDigest.getInstance("SHA-256").digest(json.file.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == "3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796") {
            "JSON host-test artifact bytes changed; review provenance before testing"
        }
    }
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach { dependsOn(verifyUnitTestJsonArtifact) }
