# Console reliability validation

M6 adds completed session checkpoints, a fresh native view on wake, actual frozen gameplay frames, reviewed portable backups, setup/settings and optional HOME eligibility. Process-death and sustained tests are explicit opt-in suites rather than part of every quick UI regression.

Build first:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite All -Cycles 50
.\tools\Test-ProcessRecovery.ps1 -Serial <authorized-device>
.\tools\Test-Stability.ps1 -Serial <authorized-device> -Minutes 10
```

The process helper generates an original GB probe, creates a separate random test DB/session, checkpoints one palette state, resumes and changes the live palette without saving. It then force-stops the running target and verifies the original PID disappeared. A second instrumentation process must start in Library, restore the last completed checkpoint paused, show the exact frozen native pixels and restore battery data. The holding test is intentionally killed; its runner termination is expected, not reported as a JUnit pass. The fresh-process assertion is the pass gate. This explicitly proves loss since the last completed checkpoint is possible.

`-Mode Reboot` instead reboots the connected device and requires a changed boot ID and `sys.boot_completed`. It preserves the owner's Android default HOME and all personal collection data. Run only when a reboot is appropriate; Android may require unlocking/reauthorizing the device before the second phase. A launch after reboot does not prove the user has selected Cartridge as HOME.

The sustained helper runs real internal GB output for the chosen number of one-minute intervals. Each interval uses input, pauses/checkpoints, manually saves/loads, resumes, performs a real screen sleep/wake, verifies saved palette/battery data, ejects to actual Filament and reinserts. It samples PSS/managed use, actual native video callback rate, public Android thermal status and battery temperature/USB power at completed ejections. It never forces a GC or calls those natural snapshots a leak verdict. Logs and original probes remain ignored under `.tools`; only aggregate results enter docs. A ten-minute USB-powered diagnostic is not a multi-hour compatibility/battery-life benchmark.

Actual results, 2026-10-10: ForceStop fresh-process recovery passed (1 verification test, 2.443 seconds after successful preparation/termination). The original test initially expected an incrementing byte; source inspection confirmed the GB probe toggles 0/1 and its expectation was corrected. The checkpoint/restored pixel and battery assertions are unchanged. Sustained/reboot/final regression results follow below.

Manual release coverage still includes: choosing HOME through Android and reverting it; different storage providers, revoked grants and full storage; real external controller unplug/reconnect; longer/warmer GBA/GBC titles and audio/latency; hundreds of distinct accepted label textures; battery use while unplugged. Original development models still need owner/art review, and optical discs/other cores are future audited increments.

2026-10-10 RP5 sustained result: PASS, 1 opt-in test, 636.783 seconds, ten complete one-minute gameplay intervals with checkpoints, input, manual save/load, actual sleep/wake, eject and real Filament reinsertion. Actual native video callback rate: 60.003–60.009 FPS. Post-ejection PSS samples (KiB): 272876, 301924, 258451, 259739, 265753, 276301, 248497, 266073, 249145, 275009 (range 242.7–294.8 MiB; first 266.5, last 268.6). Managed-use samples ranged 8248–12076 KiB. All public Android thermal samples were 0 (NONE); battery temperature stayed 28.5°C, USB plugged=2. No session/save-warning assertion failed. This used the v15 installed runtime/native code; v16 adds input threshold/list scrolling, backup revision/undo policy, Android settings access and the resume-lookup state-copy correction, covered by the subsequent full regression. A diagnostic on USB with static probe visuals is not a plateau proof or a multi-hour retail-game/battery benchmark.

Actual reboot recovery also PASS: the corrected helper confirmed a changed device boot ID, waited for sys.boot_completed and authorized ADB, then a fresh process restored the completed native frame/battery paused. Verification test: 3.346 seconds after boot. The first reboot attempt disconnected ADB before its shell exit code, so the harness now validates reboot through boot ID/completion rather than treating the expected transport close as a failure. Android's default HOME was preserved; this is not a default-HOME selection test.

Final regression initially found a reproducible 500-title recreation race (31/32 passed; focused Layout then reproduced it). Filament had a live swapchain, complete assets and its one-shot ready=true while coordinator.ready=false. The resume-record lookup had captured `state.value.copy`'s receiver before suspending on IO, then overwrote newer renderer readiness on return. The lookup now completes before reading/copying current state. The existing scale test is strengthened to eight consecutive recreations, preserving the 500-record/pool/layout checks. No timeout or assertion was removed. Final verification follows below.

Final expanded RP5 regression PASS: 32/32 tests in 189.118 seconds, `-Suite All -Cycles 50`, on the corrected v16 implementation. This includes 50 real Filament/mGBA handoffs, all three layouts, eight consecutive 500-title recreations, native GBA/GB/GBC battery/state/RTC, real sleep/cold recovery, protected backups, UI/controller preferences, migrations and scraper/pack review. No assertions were dropped to reach the pass. Build/44 host tests/lint/ten asset checks/three error-free GLBs also pass.

Live provider verification on the final build also PASS: 1 opt-in test, 3.794 seconds. The real API returns GBA regional flat-label choices and GB/GBC candidates with the exact requested platform and flat-label URLs (sampled Tetris / Zelda searches). Only metadata and URLs are requested; no commercial artwork, ROM or BIOS is downloaded. Coverage remains title/provider dependent.
