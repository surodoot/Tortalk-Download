# API26 caller signature fixture

The production `TorTalkInsetsApi30` body intentionally uses public Android 30 inset APIs.
`TorTalkInsets` loads it only inside `Build.VERSION.SDK_INT >= 30`; Android 26–29 retain
normal decor fitting and `adjustResize`.

This fixture lets API26 compilation check the caller and the rest of the native code. Exclude
the production API30 adapter when using this fixture. It does not validate that adapter's body,
platform behavior, geometry or Android runtime. The normal SDK37.1 Gradle build/lint must compile
the actual adapter; authorized Android instrumentation must verify bars, cutouts and IME.
The fixture belongs outside all Android source sets and must never enter either APK.
