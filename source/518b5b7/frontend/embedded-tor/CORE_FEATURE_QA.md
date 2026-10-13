# Core feature and accessibility audit

Baseline: `d147b72a6730e189ddc5f7fabd9a231264c10592`, Foundation #46,
https://github.com/surodoot/gyeopdam-chat/actions/runs/37852553650 .
The packaged-browser job 113568859252 and embedded Android build/lint/unit/unsigned
APK verification job 113568859477 succeeded. This audit does not claim an Android
runtime, real Keystore, TalkBack, font-scale, physical keyboard or IME test pass.

## Visible controls

| Surface/control | Implemented path and explicit limit |
| --- | --- |
| Welcome Start / Login | Native anonymous signup and capability login; encrypted signup checkpoint precedes registration; explicit key recording; remembered login defaults off. Missing endpoint, admission, Tor readiness and invalid credentials produce native errors. |
| Connection badge / Settings icon | Opens Settings. Server configuration accepts a canonical Tor v3 onion origin; changing origin logs out and never reuses credentials across origins. |
| Chats refresh / room row | Paged projected server rooms, real empty state and peer-bound encrypted room setup. Closed or blocked rooms are not silently reopened. |
| Invite navigation / create / accept | Recipient-bound native invitation creation, explicit copy and stable consume retry. Account ID shown on the ticket is labeled as its final eight characters. |
| Contact menu | Public contact QR display/image import, manual account entry, own block list, pending block requests and local history. Camera scanning is not provided. |
| Chat shield / refresh | Explicit fingerprint/QR verification, own block status and local history. A replaced identity requires explicit confirmation. |
| Chat Send / pending Retry | Native Signal encryption and durable staging; retry reuses ciphertext and message ID. Server acceptance is labeled separately from receipt/read. UTF-8 byte limits return actionable errors. |
| Older / Recent history | Bounded encrypted local history windows. Archive mode disables send, retry, polling, enrollment and remote mutation. A new invitation starts a new room. |
| Block / unblock | Own revisioned state only, durable pending intent and explicit server confirmation. Unblock never revives old rooms, invitations or relay ciphertext. Local history remains available. |
| Settings device recovery | Native atomic recovery rotates the account capability and revokes the selected old device/other sessions. Exact encrypted retries survive uncertainty; the new key must be recorded. Lost Signal keys/history are not recovered; current local-device permanent revocation is explicitly unavailable. |
| Logout / stored-data recovery | Immediate local logout, ordered stored-session deletion and an explicit best-effort remote result. Namespace recovery/reset requires native warnings; unresolved credentials are not silently discarded. |
| Privacy / home / navigation | Functional routes and transparent five-minute send window versus seven-day relay retention/local-history wording. Main Back and route changes cancel stale work. |
| Preview / quiet switches | Change generic list copy and CSS motion respectively. These are document-lifetime display preferences and reset when the WebView document is recreated; they are not security settings. No real message preview is exposed. |
| Developer options | Separate screen with explicit Tor start/stop/probe and sanitized in-memory log copy/clear. Copy requires consent. No diagnostic controls appear on the main conversation screens. |

Media, groups, camera scanning and delivery/read receipts are not offered by the
visible controls. Production server provisioning remains separate.

## Evidence and bounded follow-up

The approved stylesheet and illustration markup are unchanged. Desktop Chromium
at 390×844 verifies the original welcome heading pixels, illustration metrics and
primary-button rectangle; functional copy differs intentionally. It also verifies
bridge commands, escaped chat text, busy/blocked/archive controls and zero remote
asset requests. This does not establish identical rendering on Android providers
or at every font scale.

Source audit found two concrete issues in the baseline:

1. `ui/app.js` replaced the complete main subtree and restored focus only to the
   composer. Settings switches, route changes and state updates lost other
   keyboard focus. The follow-up preserves control identity/scroll on same-route
   renders and focuses existing semantic headings after route/room navigation.
   While composition is active, same-context state rendering is deferred until
   composition completion and final input processing. Draft clearing retains its
   exact revision/text guards. Composition events are bound to the current input
   node; detached old events cannot release a newer composition. Current native
   busy/block/archive/expiry state gates actions even before deferred DOM updates.
2. `TorTestActivity` mounted a raw ScrollView without the main Activity's system
   bar/cutout inset adapter. The follow-up reuses that existing host. A prepared
   Android check opens diagnostics without starting Tor and checks measured safe
   bounds, root insets and retained scroll content.

The follow-up includes real desktop keyboard regressions for switch activation,
Tab order, focused control/scroll retention, heading focus and composer selection.
Synthetic CompositionEvents test DOM preservation during a native state update;
these are not a real Korean keyboard or Android IME execution claim. The existing
prefers-reduced-motion and quiet CSS disable transitions/animations. Native forms
have scroll bounds, capture protection and disabled autofill/state saving.

Remaining runtime checks: TalkBack route announcements and live-message behavior;
Android system font scale and provider font fallback; keyboard appearance/hide;
small screens/cutouts/gesture bars; real composition event order; lifecycle and
renderer recreation. Host signatures and desktop simulations cannot close these.
