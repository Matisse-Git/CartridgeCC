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
  public references and real provider media categories; box art is not a flat label.
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
- Fill missing metadata atomically; never overwrite existing choices implicitly. Enrichment undo restores
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

- The cartridge, tall cartridge and disc are converted from the owner's downloads by
  `tools/import_models.py` (ADR 0019, ASSET_PIPELINE); never hand-edit those GLBs or bring back a
  generator that writes them. Keep one named label primitive, its UV contract, the template sizes and
  the removal of baked game labels. A game keeps its pooled object while it is in the window
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

- Starting from a save state (SAVE_RELIABILITY) is optional and can never stop a game from starting:
  a missing, unreadable or refused manual state means a start from the beginning and one notice, and
  the file is left alone. Restore the battery save again after a state is loaded at a start. An
  option with no core family belongs to every console; keep adapter-only keys out of core variables.
