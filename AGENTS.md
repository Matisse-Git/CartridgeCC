# Working on Project Cartridge

- Read README and the relevant architecture/decision records before editing.
- Keep work within the active milestone. Finish device feasibility before visual expansion.
- Kotlin/Compose UI, Flow/coroutine state, real native Filament glTF rendering, embedded Libretro.
  Never replace emulation with a mock, launch another emulator, or add a browser renderer.
- Inspect the source for the pinned upstream tag before calling third-party APIs. Pin versions,
  source checksums, and matching Filament material/runtime versions.
- All Filament calls and Android lifecycle changes run on Main. GLRetro JNI saves run through
  the adapter's bounded GL queue. ROM/file work runs on IO. Keep exactly one native session.
- Release and flush the old GPU surface before creating the next renderer. Do not layer active
  Filament and Libretro surfaces or share their contexts without device evidence.
- Preserve explicit, serialized state transitions. Never mark Running before an actual core frame.
- Keep UI input separate from gameplay; reserve no Android system buttons. Reset inputs on pause,
  focus loss and disconnection. Document RP5 button-layout assumptions.
- Use SAF grants; never broad storage permissions. No commercial ROMs, BIOS, proprietary labels,
  signing keys, personal device dumps, or temporary build output in Git.
- Update product, architecture, testing, and license notes with behavioral changes. Distinguish
  compiled code from hardware validation. Record device model/firmware for real device results.
- Run `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug`
  and `python tools/test_assets.py` for relevant changes. Fix failures; do not add broad lint
  suppressions or pretend native runtime was tested. Device tests are in docs/TESTING.md.
- Prefer small reviewable commits and simple interfaces. Do not rewrite working subsystems or
  introduce speculative modules. No public release until licensing/source obligations are resolved.
