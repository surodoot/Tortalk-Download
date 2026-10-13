# Reviewed Signal native stripping evidence

`signal-strip-0.105.0-ndk27.3.13750724.json` is the unchanged report from
[successful capture run 37830208166](https://github.com/surodoot/gyeopdam-chat/actions/runs/37830208166)
at source commit `0be94580bab4b9f48cd16053799fe5799517389a`.

The downloaded [artifact 11573730255](https://github.com/surodoot/gyeopdam-chat/actions/runs/37830208166/artifacts/11573730255)
ZIP SHA-256 is `ccfaf1baeefabdf7e29c61b0d45b301f8d6bbcc37e0025e5a48213ee9b01a8b2`.
The retained JSON SHA-256 is `75ad5932c19a489ab4e916d5f04d4a565b6d6b6ca02f34a46a6e3c1f08d3cb9e`.
Its original `candidate_evidence_requires_review_before_packaging` status is retained
so the capture output is not rewritten to imply that its own job approved it.

Review on 2026-10-08 checked the exact published capture script, successful job logs,
artifact ZIP digest, source commit, preinstalled NDK identity, and all four output
hashes. Every source allocated section and loader program-header hash in the report
was independently compared with the locally cached, digest-pinned official AAR.
The CI capture compared these sections/headers with both stripped outputs per ABI
and required byte-identical repeated results. No SDK/NDK installation or new license
acceptance occurred in this capture job.

The reviewed command is `llvm-strip --strip-unneeded -o <output> <pinned-source>`
using preinstalled Linux NDK `27.3.13750724`, LLVM `18.0.4`. The Gradle configuration
checks its executable and `source.properties` hashes before configuring the NDK,
then pins the exact NDK directory. The final APK check requires the four recorded
stripped hashes and original Tor hashes; silently copied/unstripped binaries fail.
The capture report is pinned in `verify_embedded_tor.py` and is not downloaded during
builds. The source AAR hashes remain independent from packaged output hashes.

Signal JNI payload is 37,839,608 bytes across four ABIs, down from 467,856,412 bytes.
This is native payload evidence, not a measured APK size or Android execution test.
Actual APK inventory/size and Android JNI loading require their separate checks.
