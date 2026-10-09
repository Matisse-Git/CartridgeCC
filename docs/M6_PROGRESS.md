# M6 — Console environment

Development implementation through M6, with dated verification and remaining release/manual gates below. This is not a public release.

## Setup, preferences and optional HOME — version 13

First launch of an empty collection offers a controller-friendly setup with folder selection,
controller settings and Continue. Existing collections are migrated as set up; setup remains
available in Console settings. B → Collection → Console settings and Tools → Settings share one
panel. Paused gameplay offers Game settings and Controller alongside resume/save/load/eject.

Menu A/B and gameplay A/B are separate persisted preferences. Gameplay shoulder swap, stick dead
zone (20/35/50%) and the two existing physical pause combinations are supported. Core settings wrap
the inspected mGBA 0.10.5 audio filter and frame-skip values in app-owned labels. Current paused core
updates are serialized on its GL thread; startup passes the same saved options through pinned
GLRetroViewData.variables. No hardware HOME/power interception or arbitrary unsupported options.

The exported HOME activity alias starts disabled. The user can enable it as a candidate and request
Android's ROLE_HOME chooser (API 29+) or Home settings fallback. Android owns the default choice;
ordinary app installation does not change it. The Activity is singleTask to avoid duplicate sessions.
About & licences presents matching checked-in notice files packaged reproducibly from docs/licenses.
It does not resolve the remaining complete-source/public-release licensing gate.

Actual RP5 Android 13 results, 2026-10-10: both APKs build, 42 host tests and lint pass. Two new device
tests pass in 6.381 s. They verify first-run completion/recreation, separate A/B choices through real
GB input/battery bytes, persisted dead zone/shortcut, actual core option values/current paused update,
notice availability and disabled/enabled HOME resolution without changing the owner's default role.
The real Android default-home selection/reboot remains an explicit user-choice test, not automated.

At version 13, sleep/recovery, backup/restore and sustained stability were still pending; later increments below implement them.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite Environment
```

Platform references inspected 2026-10-10:
[RoleManager](https://developer.android.com/reference/android/app/role/RoleManager),
[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle).

## Session recovery — version 14

Automatic full states now checkpoint alongside battery/RTC saves every 15 seconds, on pause and before background shutdown. Completed generation publication is atomic and validates game/system, integration revision, length and SHA-256. Same-Activity wake creates a fresh emulator surface and restores paused. Cold launch opens the collection with Tools → Resume session, also available in Console settings. Manual ejection clears the automatic checkpoint after a successful stop; Discard keeps manual/battery saves. Failed recovery is visible and preserves the library and previous files.

Two wake incompatibilities were fixed: a stopped Compose collector could skip the Library screen and reuse a released host, so the emulator view is keyed by identity. Upstream FrameRendered can be emitted without a core frame; the native wrapper counts real non-null video callbacks and preparation waits for two. The paused Android GL surface discards buffers, so the custom menu retains an actual PixelCopy frame instead of running an emulator loop behind it. Snapshot capture is bounded and omitted if the window is already hidden.

Both APKs, 44 host tests and lint pass. The focused recovery test passes on RP5 in 8.366 seconds, including actual KEYCODE_SLEEP/WAKEUP with screen-interactive verification, same-Activity frozen pixels, cold Activity launch, and corrupt-state rejection/discard. Earlier test timing included ActivityScenario’s 45-second finish waits for stopped Activities; closing from its resumed state avoids that test-harness delay without dropping lifecycle assertions. A cold Activity launch is not claimed as a device reboot or abrupt process-death test. Loss since the last completed checkpoint remains possible on sudden termination. See ADR 0015.

Full RP5 regression also passed: 28 tests, including 50 handoffs, in 182.603 seconds. This verifies the recovery increment together with the existing library, scraper, renderer and internal GBA/GB/GBC tests; it does not establish a long-duration memory plateau.

## Portable backup — version 15

Console settings now exports and reviews/restores bounded ROM-free ZIP backups through SAF. Logical Room records, original accepted images/provenance, history/favorites/removal exclusions, whitelisted preferences, battery/RTC/manual saves and a compatible completed session are included. Merge preserves existing choices and save files; settings require opt-in. See BACKUP_FORMAT and ADR 0016 for bounds, transfer/reauthorization and failure behavior.

Both APKs, 44 host tests and lint pass. Three focused RP5 backup tests pass in 3.394 seconds, including the actual console panel, document callbacks, recreation and tampered archive rejection. At version 15, abrupt process death and sustained operation were pending; version 16 validation follows below.

## Final validation increment — version 16

The configured dead zone now gates menu/library axis repeats as well as gameplay; the generic repeat helper no longer applies a second hard-coded threshold. A real Android-axis test covers 20%/50% thresholds and neutral/repeat behavior. Console lists bring the focused action into view. Manual state blobs in portable backups now carry the integration revision even without an automatic checkpoint. History-only backup merges do not replace an existing enrichment undo point.

The active-core force-stop proof passed in a fresh process (2.443-second verification test). The completed checkpoint's native pixels/battery restored paused, and the newer unsaved palette change was absent. `Test-ProcessRecovery.ps1` and `Test-Stability.ps1` provide reproducible opt-in diagnostics using isolated original probes; details and final measured results are in CONSOLE_RELIABILITY. The development feature scope now reaches M6; bounded validation, owner/default-HOME review and public-release gates are stated separately in ROADMAP.

Console settings also opens Android device settings through the supported ACTION_SETTINGS intent for display, audio and network preferences. Android owns those controls; background checkpoint/recovery applies while its screen is open.

Sustained RP5 test passed: 636.783 seconds, ten real gameplay/checkpoint/save-load/sleep-wake/eject/reinsert cycles. Core output measured 60.003–60.009 FPS; natural post-ejection PSS ranged 242.7–294.8 MiB (first 266.5, last 268.6), Android thermal status NONE and battery 28.5°C on USB. No native session/save warning failed. This is bounded diagnostic evidence; see CONSOLE_RELIABILITY for precise runtime scope and remaining multi-hour coverage.

Actual RP5 reboot recovery passed on v16: changed boot ID and completed boot confirmed, fresh-process native frame/battery restoration paused passed (verification 3.346 seconds). The default Android HOME was preserved. The expected ADB disconnect during reboot is handled by boot completion evidence. Selecting Cartridge as default HOME remains an explicit owner choice.

After the current-state update fix, focused Layout passes both tests in 15.679 seconds, including eight consecutive recreations of the 500-title grid. The previous failure's Filament diagnostics confirmed fully loaded/rendering resources while the coordinator flag had been overwritten. Full regression is rerun on this correction.

Final full regression PASS: 32 RP5 tests, 189.118 seconds with 50 real native handoffs and eight 500-title recreations. Both APKs, 44 host tests, lint, ten asset checks and all three GLB validators pass. The development implementation now reaches M6 with the remaining owner, broader hardware and public-release gates explicitly listed in ROADMAP/MILESTONE_REPORT.

Final live API probe passed in 3.794 seconds: GBA regional labels plus GB/GBC platform-isolated candidates and flat-label URLs. No images/ROMs/BIOS downloaded by the probe. Exact Socket parity and provider/image public-distribution rights remain unverified.
