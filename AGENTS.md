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
- Label/media/metadata choices are independent of ROM identity and the reusable media mesh. Read
  docs/LABEL_SCRAPING.md before enrichment work; preserve manual choices, provenance and offline launch.
- API/database scraping is the owner's primary artwork workflow. Local custom image import is an
  optional fallback; never report it as completion of the scraping feature. Inspect Socket/Slot's
  public references and real provider media categories. Games are shown in their cases (ADR 0029): only
  `box-texture`/`box-2D` is case art; a flat label is kept as extra media and never wrapped round a case.
- Collection length is variable; keep the visible GLB/label pool bounded and map GPU acknowledgements
  through its global window offset. Never allocate a mesh or decode every label for a full library.
- Save scrape targets before network work. Keep accepted Room choices separate from durable candidates;
  journal writes/reset need ordering across Activity recreation. No implicit network resume on launch.
- Deduplicate ROM hashes, never titles. Collection removal must preserve source files, saves and accepted
  enrichment; persisted exclusions must survive Rescan. Test legacy migrations and restore/Undo.
- Restore battery data before the first emulated frame. SaveBridge may access only the already-loaded
  core on its GL thread under the session mutex. Keep failed writes visible and previous saves intact.
- M6 console environment follows the verified M5 GB/GBC increment. Keep media/platform identities explicit; audit and test each embedded core/media increment. Keep M2/M3 physical validation open. Generate original lighting/audio from versioned source (media templates: see the ADR 0019 note below); keep
  cmgen/runtime versions matched and KTX light/texture ownership explicit. Preserve the named label
  primitive/UV contract. Measure SurfaceView presentation separately from CPU/Compose frame callbacks.
- Persist query preferences separately from canonical slots; sorting/filtering must retain focused game
  identity and saves. Index only trustworthy positive document stamps; Verify all always hashes. Never
  mark unseen documents missing after cancellation or an incomplete traversal. Count only Running time.
- Scrape one title at a time after publishing targets. Preserve v1 journal compatibility and source tags;
  preferences rank alternatives without hiding them. Re-read accepted Room values before resumed review.
- Fill missing metadata atomically; never overwrite existing choices implicitly. The owner chose automatic
  apply (ADR 0029): added games and bulk scopes take the best match, Fill missing by default; Fill all
  replaces only `automatic` choices unless "Also replace my manual choices" is ticked; one write, one Undo.
  The ScreenScraper login and developer ID live only in private preferences, never in Git, backups, packs,
  stored URLs or logs, and no developer ID is built in. Enrichment undo restores
  only label/metadata/media, not ROMs, saves, source, membership or history. Keep its previous files available.
- A filtered scrape preview must never persist a temporary collection query/selection. Test apply, cancel
  and recreation; opaque scraper pages pause 3D, actual label preview resumes it.
- The owner authorizes continuing through M6. Implement and verify one increment at a time; carry open
  physical/rights gates explicitly. Do not stop at a plan or mark untested milestones complete.
- Cartridge v1 packs use exact existing ROM hashes, verified images and bounded generated staging paths.
  Never extract ZIP names directly or let local candidates fall back to network. Keep drafts separate.

- SessionStore publishes a completed generation last; keep manual/battery saves separate. Restore requires a compatible core revision, size and hash. Key emulator hosts by view identity; native readiness requires real video callbacks. Pause-frame imagery must come from PixelCopy of actual gameplay, with no active emulator loop behind the menu. A cold Activity test is not a process-kill or reboot test.

- Backup archives contain only declared bounded records/images/saves; never extract ZIP names directly. Preserve existing files and choices during merge, require explicit settings opt-in, and exclude ROMs, roles, grants and credentials. Do not claim Room and filesystem commits are one atomic transaction.

- Report actual process death, cold Activity recreation, screen sleep and reboot as separate evidence. Diagnostic holding tests intentionally terminated by the host are not reported as passing JUnit tests. Keep owner HOME/default choices and library data unchanged during diagnostics.

- After any suspending operation, read current Flow/UI state before copying changed fields. Do not capture a state-copy receiver before IO and overwrite intervening readiness/input updates. The 500-title recreation regression guards this ordering.

- The interface is the console shell (ADR 0018, docs/ux). `ConsoleShell` owns the destination stack and
  every button's meaning; `CollectionCoordinator` owns sessions and the shelf projection and must not
  learn about menus again. Rows are data addressed by id — never add behaviour by list position. A new
  action goes in exactly one place; a new setting needs a real effect, a stated scope and a device check
  that reads it back. Build screens only from `ui/` tokens and components. Layouts must
  keep the focused game's identity; a shelf the player turns to opens on its first game (ADR 0020). Opaque pages pause Filament; sheets about the focused object do
  not dim it. Do not show a capability the adapter lacks (multi-disc, rewind, analog pads).
  Re-run the UX tour (`tools/Test-Device.ps1 -Suite Tour`) and look at its screenshots after any
  visual change; compiled is not seen.

- PS1 CUE/BIN imports require a granted folder and bounded, generated private bundle paths. Rehash
  tracks on every scan, preserve optional validated SBI, and publish only complete bundles. Never index
  raw BIN tracks as games. Keep per-system core revisions in states, checkpoints and backups; retain
  existing mGBA state compatibility. BIOS import is explicit, verified and excluded from archives.

- Every game is shown in its retail case (ADR 0029, ASSET_PIPELINE). The cases come from
  `tools/generate_cases.py`, the one generator ADR 0019 allows, which writes only case files. A downloaded
  case goes through `tools/import_models.py` and its rules: no baked game print, and never hand-edit a
  GLB. Keep one named label primitive per case whose UVs unfold back, spine, front as `Media.cover` says,
  and render each new GLB with a test cover and look at it before it reaches the device. A template is one
  `Media` entry (file, label height, on-screen scale, cover); the renderer names no object, and no two
  shapes share a label height. A console's case is `GameSystem.media`, or `media(fileName)` by the market
  the file is named for, never a scraped title; that choice is presentation only and nothing else may
  ask for it. A game keeps its pooled object while it is in the window
  (`CollectionLayout.slot`): stage labels by key and owner, never by window position, and never draw
  an object with another game's label or the template's. Re-run `BrowseStabilityTest`
  (`-Suite Browse`) after any renderer, window or label change.

- The stage changes hands only behind the closed screen (ADR 0020): close it before a surface is
  released, open it only on a library that has drawn or a game that shows a picture, and show no
  loading text. Keep input locked while it is closed. Never write the selection per browse step, map
  the library on Main, or let a selection-only write re-announce the library; measure with
  `LibraryPacingTest` (`-Suite Pacing`, and `-e games 3000`) before blaming the GPU. The right stick
  is `AXIS_Z`/`AXIS_RZ` on the RP5 and belongs to the interface outside gameplay only. A focused row
  must look focused whatever is behind it and whether or not it can be used.

- The Android apps (ADR 0021) are the library's sibling, never part of it: no Room rows, console
  records, labels or 3D objects for an app, and games are still run only by the embedded cores. B
  opens Apps only as the last branch of `ConsoleShell.dismiss`, with nothing left to close; do not
  add a button, tab or menu row for it, and keep Android's Back (`systemBack`) from opening it.
  Reach Android only through `AppSource`/`LauncherApps` with the MAIN + LAUNCHER `<queries>`; never
  request `QUERY_ALL_PACKAGES`, build a launch intent by hand, or store the app list. Address apps
  by `package/activity`, read them off Main, and unregister the callback in `ConsoleShell.close()`.
  Interface tests and screenshots use `FixtureAppSource`; nothing committed may show what the owner
  has installed. Re-run `AppsLibraryTest` (`-Suite Apps`) after any change to back handling.

- Starting from a save state (SAVE_RELIABILITY) is optional and can never stop a game from starting:
  a missing, unreadable or refused manual state means a start from the beginning and one notice, and
  the file is left alone. Restore the battery save again after a state is loaded at a start. An
  option with no core family belongs to every console that can make a state; keep adapter-only keys
  out of core variables.

- The quick save is `saves/<id>.state` and stays byte compatible; listed saves, the previous quick save
  and thumbnails live in `saves/<id>.slots/` through `StateSlots` only (ADR 0028). Publish a listed save
  by its record last, keep the quick save it replaces before writing a new one, and never delete or
  rewrite a state from another core build. A thumbnail is optional and can never fail a save. Quick
  combos need two or more gamepad buttons and may not overlap each other or a pause shortcut; they
  pass through like the pause chord and stay latched until let go. Re-run `-Suite Saves` after any
  change to states, combos or the pause menu.

- A console is one entry in `domain/GameSystem` and an engine one entry in `domain/Core` (ADR 0022,
  docs/CONSOLES). Never decide behaviour by comparing a system or core to a constant elsewhere: add a
  field. Enum names are stored ids: append, never rename or reorder. `media` is presentation only;
  `bundle` says how a game is stored. A new core goes through `cartridge_core()` with a pinned archive
  hash, its licence text in docs/licenses, an original probe in tools/probe and a device test; a
  console that has not run on the device is described as implemented, not playable. Firmware is the
  owner's: accept it by content in `FirmwareStore`, never bundle, download or back it up. Identify a
  file by its first bytes before copying it; keep PlayStation identities computed as they were. Keep
  docs/CONSOLES current, including what is blocked and why. Re-run `SystemRegistryTest`, `-Suite Cores`
  and `-Suite Saturn` after any registry, importer, core or shim change.

- Flycast, ARMSX2 and Dolphin draw for themselves (ADR 0023, 0024, 0026). Never edit an engine's
  source: what the host assumes is kept by `cartridge_shim.c` (GL state saved and restored around
  the host's copy, the host's framebuffer bound only where the screen is bound, silence reported as
  a repeated frame, the base size the core really draws, the core paced by its own sound with
  nothing held thrown away, and the order a game and its context are let go in) and by `Core`
  fields (`analog`, `emptyPorts`, `data`, `snapshots`, `draws`, `sheets`). Do not bind, pace or
  unload around the shim elsewhere. An engine with `draws` is unloaded on the thread it ran on, and
  Compose takes that thread away before `onDestroy` is reached: a loaded game is released from
  `MainActivity.endSession`, never later. Dolphin runs on one thread (`dolphin_main_cpu_thread`).
  ARMSX2 is unloaded before its context is destroyed (`CARTRIDGE_UNLOAD_FIRST`), has no states, is
  given a game by its canonical path, and has `allowNativeHeapPointerTagging="false"` in the
  manifest as its own app does: none of these is undone without a game run that shows why it is
  safe. A black screen or dead pad on one of them is the host's until a host-side run of the probe
  says otherwise. Keep ARMSX2 and Dolphin as builds of their own (`cartridge_external_core`); their
  target names collide in one tree. PlayStation 2 cannot start without the owner's BIOS and has no
  replacement: take one by what its own file table says (`Firmware.proves`), never from a list of
  dumps; its device tests are skipped where `files/m1-owner-firmware` holds none, and neither a
  BIOS nor where the owner keeps theirs goes in the repository. Offer a state only where
  `snapshots` allows, and never capture one during play unless it is `Always`. A disc proves its
  console by its bytes and length before its folder is asked. Whatever a CUE or GDI names is that
  disc's track, whatever its extension (`SheetTracks`). Name all four controller ports for a core
  with `emptyPorts`. An original test program proves a start, not a game: every fault the owner's
  games showed was invisible to one. After any shim, host, adapter or engine change re-run
  `-Suite Dreamcast`, `-Suite PS2`, `-Suite GameCube` and `-Suite Discs`, then `RealGameCheck` with
  an imported game on each of the three (docs/TESTING), and say which games were not run. Write a
  game's result as that game's, never as the console's, and keep the owner's games out of the
  repository, their names included.

- Sleep is how a handheld is put down, and it must never cost a game its place. Leaving the app
  writes a checkpoint and stops the engine only where the core can be captured; a core with
  `snapshots = Never` stays loaded and paused (`CollectionCoordinator.keptWhileAway`, the only
  place that decides it). A pause must complete whatever the screen is doing: it is what writes the
  save, so nothing optional in it (the copied picture) may throw. Check a change to pausing,
  backgrounding or the session lifecycle with a real sleep (`RealGameCheck -e sleep N`), which the
  device's power button does and `moveToState` only approximates; the USB link drops after one, so
  read the result from the device's log.

- Freeze and fast-forward (ADR 0031) are combos like the quick ones (`QuickCombo`): a freeze is a pause with
  no menu (`EngineStatus.frozen`), fast-forward is the host's whole-number `frameSpeed` with the sound off and
  only where `Core.fastForward` says so. The pause menu, sleep, leaving and ending a game return to normal
  speed, unfrozen. Nothing but `ui/GameBadge` rests on a game. Re-run `-Suite Saves`, the tour and
  `RealGameCheck -e fast N` after a change to them, and turn `fastForward` on for an engine only after
  one of the owner's games shows it.

- Shader presets (ADR 0027) are optional and never stop a game: a preset that is missing, unreadable or
  refused by the GPU gives the normal picture and one notice, and the choice stays saved. A preset is
  checked in its own GL context before it is saved. Pass sources keep their meaning: the ways `link`
  tries are compile variants, the glslang/SPIRV-Cross translation and `GlslCompat`'s rewrites, never
  edits of a shader's behaviour. The library is downloaded only when the owner presses Update, never
  committed, bundled or backed up; it replaces the installed one only when complete. Re-run
  `-Suite Shaders` after any change to presets, the host's drawing or the shim.

- The host is LibretroDroid 0.14.0 built from its source in `libretrodroid/` (ADR 0027). Its
  submodules are fetched by archive hash, never copied in; every change from the tag is listed in
  `libretrodroid/UPSTREAM.md` and keeps the host's API unless the change says why. Its native code
  is optimised in every variant. After a change to it, run the regression and `RealGameCheck` on
  one of the owner's games per console, and compare presentation with the previous build.
