# M6 — Console environment

Active, proceeding through focused increments after M5. This is not a public release.

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

Sleep/recovery, backup/restore and sustained stability are the next increments; M6 is not yet complete.

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
