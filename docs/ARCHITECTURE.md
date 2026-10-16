# Architecture

## Console registry (2026-10-11, ADR 0022)

A console is declared once, in `domain/GameSystem.kt`, and everything else reads that declaration.
Where an older section below says "PS1" to decide behaviour, read "a console whose definition says
so". [CONSOLES](CONSOLES.md) lists the fields and the status of each console.

| Piece | Owns |
|---|---|
| `domain/GameSystem` | a console: title, maker, media template, core, formats and their content checks, private extension, bundle or file, size bound, folder names, provider name |
| `domain/Core` | an engine: packaged library, state-binding revision, options heading, firmware, fixed variables, own-files flag |
| `domain/ConsoleCatalog` | folder hints and name-only classification, derived from the registry; the list of recognised-but-unplayable consoles |
| `storage/RomImporter` | one file: reads 512 bytes, lets `GameSystem.detect` name the console, then copies within that console's bound |
| `storage/DiscImporter` | a bundle: launcher under its real extension, tracks under generated names, console from the disc's own first sector, then the folder |
| `storage/SheetTracks`, `DirectoryScanner` | a folder is listed first; what its CUE and GDI sheets name is skipped as tracks before anything is imported |
| `storage/FirmwareStore` | the owner's console files: accepted by content, stored under the name the core looks for |
| `cpp/cores.cmake`, `cartridge_shim.c` | cores that need only the Libretro ABI, with shared frame and PCM counters |
| `cpp/flycast.cmake`, `armsx2.cmake`, `dolphin.cmake`, `core_wrap.cmake` | the three engines that draw for themselves (ADR 0023, 0026): one in this CMake tree, two as projects of their own, all given the second build of the shim |
| `storage/CoreData` | an engine's own packaged data, unpacked into the private system folder once per build of its core |
| `shaders/ShaderLibraryUpdater`, `GitHubShaderSource` | the Update button's work: newest commit, bounded download and unpacking, swap into `files/shaders/library` (ADR 0027) |
| `shaders/PresetReader`, `GlslCompat`, `ShaderLibrary` | a RetroArch GLSL preset read as RetroArch reads it, a GLSL ES form of each pass for when its own does not compile, and the images decoded for the host (ADR 0027) |
| `libretrodroid/` (module) | the host: LibretroDroid 0.14.0 built from its source since 2026-10-16 (ADR 0027), GLSurfaceView, final drawing pass, audio and input; its changes from the tag are listed in its `UPSTREAM.md` |

Identity: the enum name is the stored id. GBA, GB, GBC and PS1 keep their names, order and hashes.
A later console's disc is hashed with its console's name first; PlayStation's is not, so no existing
entry moves.

Detection order for a file: its own extension; what a shared container proves about itself
(`Format.proves`: Nintendo's disc marker, a length beyond any CD, the unit a CHD packs); for a CUE
the first data sector; then the console its folder names; PlayStation when nothing else speaks.
`.exe` and `.md` are tried without being announced; `.bin`, `.cso`, `.ciso`, `.dol` and
`.elf` only in their console's folder.

Native readiness: `cartridge_core()` renames a core's video-refresh and audio-batch setters at
compile time and links `cartridge_shim.c`, which counts produced frames (a non-null buffer, or the
hardware-frame marker) and non-silent PCM. `SaveBridge.frameCount` reads `cartridge_video_frames`,
falling back to mGBA's and PCSX's own counters. A core without mGBA's save-data extension is read
through `retro_get_memory_data` directly; only mGBA needs its clock synchronised.

Sessions: `LibretroEngine` takes the library, the fixed variables and the state envelope from the
game's core. A core flagged `ownFiles` gets `saves/<content hash>.files/` as its save directory.
`ManualState` wraps every core's state but mGBA's with the build that wrote it.

Firmware: `ConsoleShell.firmwarePresent` is the set of private file names in place.
`firmwareReady(core)` is false only for a core that requires a file and has none; then A on a game
opens `Destination.Setup` instead of inserting, the game's details carry an "Add … BIOS" row, the
import result lists the core under `ImportFlow.Done.setup`, and Settings › Gameplay marks its rows.
A launch failure offers the file whenever one of the core's files is absent.

Settings › Gameplay is generated: one heading per `Core`, its firmware rows, then its options.

### Cores that draw for themselves (ADR 0023)

Flycast, ARMSX2 and Dolphin render with OpenGL ES in the context LibretroDroid owns (ARMSX2 on a
thread of its own, in a second context that shares the host's). The host was
written for cores that hand it a finished picture, so the second build of `cartridge_shim.c`
(`CARTRIDGE_HW_FRAMES`) stands between the two at four Libretro entry points:

| Entry point | What the shim adds |
|---|---|
| `retro_set_environment` | notes where the host's framebuffer is asked for; replaces a nominal base size with the size the core really draws |
| `retro_run` | binds the host's framebuffer where the screen is bound, and only there; does not run the core while enough of its sound is still waiting; reports a repeated frame when the core says nothing; hands the host one run's worth of sound |
| `retro_set_video_refresh` | around the host's copy to the screen, saves and restores every piece of GL state the copy touches, sixteen texture units and the unpack state included (a shader preset's passes, ADR 0027) |
| `retro_set_audio_sample_batch` | holds the core's sound for `retro_run` to hand on, and counts it |
| `retro_unload_game`, and `cartridge_unload` beside it | lets the game be unloaded on the thread it ran on, before the host does it from Main and finds nothing left to do; for ARMSX2 (`CARTRIDGE_UNLOAD_FIRST`) the game before its context, the reverse of the host's order |

What the second and fourth rows are for was learned from the owner's games (ADR 0024): these
engines emulate until a game shows its next picture and the host never waits for sound, so a game
is paced here by its own sound; and Dolphin frees the signal stack of whichever thread unloads it.
A core with `snapshots = Never` is not stopped when the app leaves the foreground: its session
stays, paused, and `CollectionCoordinator.keptWhileAway` is the one place that decides it. The
manifest turns native heap pointer tagging off for the process, as ARMSX2's own app does.

Order within a session, on Main unless noted: unpack the core's data (IO); create the GL view with
the game's canonical path (for a core without `sheets`, the first track of a CUE); after the game
is loaded and before the first frame, plug the pad into port 1 for a core with `emptyPorts` (GL
queue); restore saves; run.

The end of a session that the Activity's own end brings (ADR 0026): from Android 10 the lifecycle
is told of the end before `onDestroy`, Compose disposes of its views there, and the emulator's view
takes the core's thread with it. `MainActivity.endSession` therefore runs from
`onActivityPreDestroyed`, registered after the lifecycle's own callbacks so that it is told first,
and again from `onDestroy` for older Androids, where the view is still attached at that point.
`LibretroEngine.releaseNative` unloads a core with `draws` on its thread while the view is in a
window, and from the calling thread when it no longer is.

Input: for a core with `analog`, `ControllerRouter` sends both sticks to the game through the
host's two stick channels and the D-pad only as the D-pad. There is no channel for trigger travel.

Snapshots: `Core.snapshots` decides whether the running-game checkpoint, the paused checkpoint, Save
State and *Start from save state* exist for a console. The engine and the pause page both read it;
neither compares a console to a constant.

## Console shell (2026-10-10 redesign)

The interface layer was replaced; emulation, rendering ownership, persistence and import mechanics
were not. ADR 0018 records the decisions; [docs/ux](ux/REDESIGN_SPEC.md) records the design. Where
an older section below describes the Tools shelf, the collection menu on B, `LibraryMenu`/`Details`
coordinator screens, `ConsolePanel` or `LibraryPanels`, this section supersedes it.

| Layer | Owns | Does not own |
|---|---|---|
| `MainActivity` | window, lifecycle, activity-result pickers, key/motion delivery, hosting the Filament and emulator views | what is on screen, what a button means |
| `shell/ConsoleShell` | the destination stack, focus by row id, every controller role, presentation preferences, import flow state, the operations that need a `Context` (scan, import, backup, BIOS) | session or library truth |
| `shell/ShellPages` | rows for every destination, derived from state on each read | any stored state |
| `session/CollectionCoordinator` | the serialized operation, the native session, the shelf projection and focus identity | menus, cursors, overlays |
| `apps/AppLibrary` | which Android apps are installed, the app search, the focused app, decoded icons | what a button means; anything about games |
| `apps/InstalledApps` | the calls to Android's `LauncherApps` | state |
| `ui/` | tokens, components, library chrome, overlay rendering | decisions |

Input: `ControllerRouter` still sends each event down exactly one route (Library, Gameplay, Menu,
Text, Locked) and still polls held directions on a Choreographer callback, but it now reports
buttons by role — select, back, system back (Android's Back and Escape), alternate (X), secondary
(Y), shoulder, menu (Start), adjust (left/right in a menu) — and `ConsoleShell` assigns the meaning. `ConsoleShell.inputMode()` is the
single place a mode is chosen. The shell is told about every coordinator and engine change through
`sync`, where it clears overlays when the scene changes (library, game, label preview), reports a
finished scraper preview, counts launches and raises the launch-recovery sheet.

Shelves: `library/Shelf` projects the canonical snapshot inside the coordinator after
`LibraryQuery` sorting. The shelf and a per-shelf focused content id are presentation preferences
(`shelf`, `shelfFocus`); Room still stores only canonical order, the selected slot and the query.
Focus resolution order is: explicit target, the game already focused, what this shelf last showed,
the stored selection. Turning to another shelf is the exception: it always opens on its first game
(ADR 0020). The selection is written to the database once browsing has rested for 400 ms, and before
anything that reloads the library; the repository maps rows off the main thread and does not
announce a selection-only write, so a held D-pad never re-reads the library. A scrape preview of a game outside the view still uses a transient projection
that is never persisted.

Rendering: Filament keeps rendering beneath translucent sheets and is paused beneath opaque
pages (`ConsoleShell.base != null`) and the artwork workflow. `CartridgeSurface.presentation`
moves the focused object aside for Details, its sheets, the label preview and the welcome.
Label material instances carry a polygon offset because a label lies in, or 0.05 mm above, its
recess floor.

Starting and leaving a game (ADR 0020): `CollectionState.curtain` closes the screen while the stage
changes hands. `insert()` waits for the object to leave, closes the screen, releases Filament,
prepares the core, and opens only when `RendererHandoff.awaitPicture()` has seen something other
than a flat white or black frame in the middle of the emulator's surface (two seconds at most).
`eject()` closes it over the game; `rendererReady` for the rebuilt library opens it and the
ejection plays then. `ui/Curtain` draws the owner's choice of Fade, Iris or Shutter, or a cut under
reduced motion. Cartridges and discs both leave through the bottom of the screen; there is no
receiving slot. The right stick (`AXIS_Z`/`AXIS_RZ`, routed by `ControllerRouter` outside gameplay)
turns the focused object through `CartridgeSurface.spin` on the shelf and beside its details.

Browsing (ADR 0019): a game keeps one pooled object for as long as it is in the window.
`CollectionLayout.slot(index)` picks the object from the shelf index alone, out of
`CollectionLayout.POOL` (15) per media template, and the window is what rests on screen plus what
one step brings in: five objects for the carousels, five rows for the grid. The Activity keeps
decoded labels in a map by image key (a decoded accepted label, or a `TypesetLabel` of the title)
and stages `system, owner, key, pixels?` per window entry through `CartridgeSurface.setWindow`.
`CartridgeLabels` binds a texture only for an object that entered the window, keeps a game's
current label up while its replacement decodes, and never draws an object with another game's
label or the template's: an object that changed hands stays hidden until its own label is bound.
Arriving objects were labelled while still off screen, so a step re-labels nothing in view. Grid
rows outside the three on screen stay ready and grow in or out as the grid scrolls.

Options: `environment/GameOptions` and `OptionStore` resolve default → console → game.
`LibretroEngine` asks for them per `GameRequest`; `cartridge_screen_effect` selects the
LibretroDroid shader and is never sent to a core; PlayStation's BIOS and memory-card variables
are applied last. An option with no family belongs to every console. `cartridge_start_from_state`
is the one such option: when it resolves to enabled and no session is being resumed, `prepare`
reads the game's manual state, loads it before the first frame, and then restores the battery
save and clock again, because a state carries the battery memory of the moment it was made and the
cartridge must keep what was saved last. A missing state is an ordinary start. An unreadable or
refused one resets the core, starts from the beginning and reports once through
`EngineStatus.notice`; the file is not changed.

Android apps (ADR 0021): `Destination.Apps` is an opaque destination on the shell's stack. The back
role opens it only as the last branch of `ConsoleShell.dismiss`, after everything that can be
closed has been, and `openApps()` additionally requires an empty stack, `InputMode.Library` and no
library search. Android's Back arrives as its own role and never opens it. Inside Apps the shell
maps the existing roles onto `AppLibrary`: up/down and left/right move by rows and columns of the
grid the page really drew, A starts the focused app, Y opens the console's text entry as a search,
B clears a search and then pops. The coordinator is not involved, so the shelf, the focused game,
the viewport generation and the paused Filament surface are exactly as they were when Apps is
popped. `AppLibrary` reads `AppSource` off the main thread, folds overlapping requests into one
further read, sorts with the locale's `Collator`, addresses focus and icons by `package/activity`,
and stores nothing but the focused id (`appsFocus`). `InstalledApps` implements `AppSource` with
`LauncherApps` on the application context: list (intersected with real MAIN + LAUNCHER activities),
icon, `startMainActivity`, and a `Callback` that is registered on first use and always removed in
`ConsoleShell.close()`. `MainActivity.onResume` asks for a re-read once Apps has been shown;
`onPause` saves the focused app; `onSaveInstanceState` records that Apps was open so a rebuilt
Activity returns to it after its first library `sync`. Starting an app pauses the Activity like any
other loss of foreground: with no game running, `CollectionCoordinator.background()` only settles
the focus write, and nothing is released or reloaded on return.

Screen wake: the window no longer sets `FLAG_KEEP_SCREEN_ON`. The Compose root keeps the screen on
while a scan or artwork search is running; the emulator's own view keeps it on during play.
`DebugCollectionActivity` restores the flag because instrumentation injects input without user
activity.

The PS1 increment adds explicit system-selected cores and immutable scoped disc bundles without
changing the single-session/surface ownership strategy. See ADR 0017 and PS1_PROGRESS for the source
pin, CUE track confinement, BIOS ownership, bounded media pool (45 instances since ADR 0019; 105, fifteen for each of seven templates, since ADR 0025) and per-core save revisions.
Historical M0–M6 sections below describe their original scope; PS1 details take precedence where noted.

One Android module keeps the feasibility work reviewable. Packages separate pure navigation/session
logic, input routing, file handling, renderer ownership and the emulator adapter. MainActivity owns
Android views/lifecycle; an Android-free CollectionCoordinator owns selection, operations and handoff
decisions. Native objects are never retained in a ViewModel or global UI singleton. RoomCollectionRepository
persists ordered slots, ROM identities/source URIs, focus, query preferences, history and accepted label recipes. Schema JSON is
versioned under app/schemas; no destructive migration fallback is permitted.

## State and input

`SessionMachine` enforces Idle → Preparing → Running ⇄ Paused → Stopping → Idle, with Error exits.
`LibretroEngine` exposes Flow status and serializes native operations with a Mutex. Preparing includes
core/game setup, SurfaceCreated plus the pinned successful render-lifecycle observer registration,
battery restoration/readback before emulation starts,
then two actual native video callbacks. Upstream FrameRendered may be a zero-frame timing step; the wrapper counts produced video separately. Error recovery destroys the native
session and returns through cleanup to Idle before retry. A process-wide atomic claim prevents a second
LibretroDroid native frontend from being initialized concurrently.

The coordinator independently tracks Library, LibraryMenu, Details, Inserting, Handoff, Emulator, Ejecting and LabelPreview,
selection, viewport generation and busy state. The
busy flag locks input during preparation/save/ejection. It does not declare an emulator Ready state.
`ControllerRouter` sends each event to exactly one route: Library, Gameplay, Menu or Locked. Android
keys go to GBA port zero; sticks/HAT use a digital D-pad mapping with a 0.35 dead zone. Navigation is
polled by an Activity Choreographer callback: first action immediately, first repeat at 300 ms,
following repeats at 110 ms. X adds/selects a game, B opens the collection menu, Y opens scraping, Start opens the folder picker
and Select invokes the image picker in Library; label preview routes
D-pad/A/B to its own actions. Gameplay never receives those UI actions.
Pause clears all previously forwarded keys/axes. No HOME interception or system-button assumptions.

## Graphics ownership

Filament uses a SurfaceView/UiHelper swapchain. A bounded fifteen-object pool holds each supported physical template, sharing meshes and shell textures/materials. Three carousel objects or up to nine grid objects rest on screen; each shelf index of the staged window owns the pooled object `CollectionLayout.slot` gives it (ADR 0019). CartridgeLabels duplicates only the named label material per staged instance
and owns its replacement SRGB8_A8 texture. GLB/image file reads and label decoding use IO; every Filament/JNI resource call stays on Main. Texture
resources load asynchronously and renderables are added when ready. A Choreographer callback updates
root transforms and draws; the focus spring retargets in place. Backgrounding pauses that loop.

M2 loads a CollectionAssets bundle on IO once per Activity: refined cartridge, independent receiving-slot
GLB and original prefiltered studio KTX. Two bounded ResourceLoaders asynchronously prepare the two assets.
Visible cartridge instances share mesh/plastic/normal/roughness resources. The official matching
KTX1Loader supplies diffuse SH and specular cubemap; both GPU resources are destroyed at handoff. No
skybox or runtime prefilter work runs during navigation. The slot lies beyond the far clip at rest.
MediaMotion owns phase curves and shared durations; Compose advances its linear progress while Filament
applies lift, alignment and descent. Motion off also snaps/reset the carousel spring. Optional original
SoundPool audio is Activity-owned and independent of the core; Main lifecycle guards and close release it.

The collection's touch tools live on an explicitly opened shelf whose visibility survives Activity
recreation. Physical shortcuts still work with it closed. Enrichment and library semantics are unchanged.
Debug-only frame diagnostics distinguish CPU submission from SurfaceFlinger actual presentation timestamps.
They retain only bounded-duration original-artwork samples, never owner library/scrape data.

On insertion, the shell closes Filament: remove the frame callback, detach UiHelper, destroy swapchain
and flush, cancel asset loading, destroy asset/material/light/camera/view/scene/engine resources. Only
after the completion barrier does it create LibretroDroid's separate EGL/GLSurfaceView. There is no GPU
context sharing or active 3D rendering behind gameplay. Compose draws ordinary UI above the active view.

Ejection pauses the GL thread/audio, captures SaveRAM on the emulator GL queue, writes on IO, destroys
the native frontend, removes the GLSurfaceView and waits for detach. Filament then recreates with the
existing selected index and labels. Viewport generation rejects readiness from an old surface.
Explicit host removal also completes while backgrounded; cleanup does not
depend on a future Compose animation frame. Close methods are idempotent.

TextureView compositing is deferred. For M0, separate SurfaceViews are the smaller correctness test.
The owner confirmed the pause menu works on RP5; overlay resizing/aspect and lifecycle stress remain
checks. The native 50-cycle handoff probe passed. Context sharing/crossfade were not tested. Native
context recreation in upstream GLRetroView remains a known inspection risk for suspend hardening.

## Emulator adapter and files

`EmulationEngine` describes prepare/start-by-surface, pause, resume, stop, input, states, RAM and options.
Prepare loads both the single packaged core and a ROM. The UI never handles core internals; the separate
surface Flow is specific to the Android host. Core initialization occurs only after a real surface exists.

The adapter owns a fresh LifecycleRegistry per session and installs event/error collectors first.
The session remains STARTED until SurfaceCreated, successful observer registration and battery
restoration/readback complete; only then
RESUMED permits the first retro_run. Pause uses the registry to stop the render loop/audio. Save calls
queue a coroutine result on the GL thread and time out after three seconds. SaveBridge captures/restores
battery memory and captures states through the already loaded core's public ABI; state loading uses the
upstream checked method with `useEmulationThread=false`. No leaking frontend capture buffer or ignored
SRAM rejection result. This also avoids the upstream latch hanging on a thrown native error.
Timeout recovery stops the GL thread before destroying native state; no concurrent save/destroy is allowed.
SurfaceCreated alone is insufficient in pinned 0.14.0: initializeCore can swallow a failure and still
emit it. Prime the private registry's coroutine observer, then require the additional successful
RenderLifecycleObserver posted before that event. Check with a synchronous Main Handler after that
upstream synchronous post, avoiding coroutine async-message sync-barrier bypass. This audited barrier avoids touching
uninitialized mGBA memory while async error delivery races; re-audit it for any frontend version change.

Files use a stable SHA-256 ROM-content identifier. SAF document import enforces `.gba`, size 192 bytes
through 32 MiB, and bounded streaming into a private working copy. Save files are `<hash>.sram` and
`<hash>.state`; invalid identifiers are rejected. Writes sync a temporary file then atomically replace
the previous file. Reads/writes are bounded (128 KiB battery / 16 MiB state), with a shared directory
guard and independent temporary paths. Changed battery bytes checkpoint every 15 seconds during play,
on pause, after state save/load and on normal stop. Save failure stays visible without destroying usable
gameplay; successful retry clears it. Save-state core-version compatibility is not encoded yet; mGBA is pinned.

## Lifecycle limits

Activity focus loss/disconnection clears UI input and pauses gameplay. On Activity pause the coordinator
cancels an unfinished insertion, waits for in-flight save/import writes, then ends any session after RAM
persistence. Cancellation cleanup uses NonCancellable while the Activity remains alive. On destruction an
idempotent synchronous native release is the final safety barrier. It cannot promise saving after
process kill beyond the latest completed battery checkpoint, and full-state restoration after rotation/suspend is deferred. Room restores assignments,
labels and focus after recreation; an ended game can be inserted afresh. Preview drafts are not accepted
implicitly on recreation. These limits require no fabricated gameplay fallback.

ADRs record dependency, surface, storage and label decisions. Coordinator unit tests cover background
cancellation, concurrent-save ordering, repeated launch input, missing files and recoverable state errors.

## Implemented local artwork boundary

LabelStore imports bounded PNG/JPEG/WebP streams, checks type/dimensions and promotes unchanged originals
by SHA-256. A LabelRef stores content ID, source URI, Fit/Crop and quarter-turns separately from the ROM
identity. Accepted recipes are Room writes; preview/cancel never changes the stored label. Derivation
produces explicit RGBA bytes at 512×256 with a charcoal Fit background. The Activity caches at most six
derived buffers; Filament owns at most three current replacement textures/material instances and frees
them before destroying the GLB. Mipmap generation explicitly requests GEN_MIPMAPPABLE usage.

Apply is disabled until the renderer acknowledges the current recipe/generation after a rendered frame.
Failed image decoding/upload leaves an error and keeps the saved choice. The RP5 PixelCopy test proves
red/blue GPU labels stay independent and restore after cancellation/recreation/database reopening.
Automatic EXIF orientation, arbitrary crop positioning, cache garbage collection and accepted-version
undo are deferred. PNG/WebP originals are not compressed to KTX2 at runtime in this increment.

## Implemented API artwork and metadata boundary

ArtworkProvider's RetroBase adapter supplies real GBA candidates and HTTPS images. ScrapeCoordinator
owns the separate reviewed search queue; ScrapePanel uses Compose, while flat-label choices use the
existing Filament preview. Candidate API data is separate from Room's accepted selections. The SHA-256
ROM key never changes when a provider match/label/title is selected. Metadata fields, regional label
and additional-media categories merge independently, preserving unchecked choices. Room v2 adds three
nullable JSON columns with an explicit tested v1→v2 migration; schemas 1 and 2 remain exported.

Retain original images and crop/fit recipes separately from derived GPU textures. The renderer shares
the cartridge mesh but binds a distinct label material instance per visible game; label decoding runs
on IO, GPU creation/destruction on Main. SAF and remote bytes share LabelStore's bounded stream importer.
Cache accepted files for offline use and bound GPU texture memory rather than decoding on every move.

Search uses SHA-1/size/platform lookup, then title queries; corrected queries skip the hash guess.
No ROM upload, embedded secret or account. HTTPS hosts, image/API sizes and decode dimensions are bounded.
Candidate JSON expires after one hour and is bounded to 32 files/32 MiB. Requests are sequential with
at least 1.1 seconds between API calls; HTTP failures remain visible. Thumbnails decode off Main.

The queue snapshots/deduplicates current game IDs. Only explicit choices can replace accepted fields,
labels or media. Cancel stops pending work without rollback of accepted choices; failed/unsearched
entries can retry without repeating successes. ScrapeJournal now saves targets, corrected queries,
error/completion status and the review index in an atomic manifest, with candidates in per-title files.
Only current candidates remain in RAM. Reopen → Resume loads completed candidates and continues
pending searches. No automatic network work on launch. Draft previews/checkboxes are not accepted or
restored implicitly; a new scope replaces queue progress, preserving Room choices. File writes share
a mutex and sequence ordering across Activity instances. One saved batch; individual files ≤8 MiB.

Room v3 adds remembered SAF directories; v4 adds removed-game identities, with v1→v2→v3→v4 migrations.
Initialization collapses legacy repeated hashes, compacts slots and preserves focused ROM identity.
Different hashes with the same title remain separate. Remove/restore transactionally update exclusions,
ordered entries and focus, retaining game records, ROMs, saves and enrichment. Undo retains the previous
ID/position in Activity state; explicit Add restores exclusions after reopening. Rescans cannot unhide games.
DirectoryScanner traverses child
document URIs on IO with persistent read grants, depth/count/ROM-byte bounds, per-file commits and
cancellation checks. New identities append; existing identities keep labels and
metadata. Sources are never written or deleted, including when a rescan reports revoked/missing roots.
Snapshot mapping is linear in collection size; selection count follows the repository without resetting
focus. Rendering/label decoding stays bounded to the visible three-object window. An empty collection
has one placeholder; inactive pooled instances lie beyond the far clip and label buffers remain padded
to three. Registered roots
persist, while an interrupted scan restarts/deduplicates on explicit Rescan.

Selected console scopes and scrape packs remain planned. M3 adds incremental indexing/root management
and reviewed enrichment below; native surface ownership stays unchanged.

## M3 projection, index and history

Room v5 extends GameRecord with favorite/playMs/lastPlayed, CollectionRecord with sort/favoritesOnly/search,
DirectoryRecord with name/lastScan/error, and introduces indexed_documents keyed by root/document URI.
Explicit 4→5 migration joins the existing chain; no destructive fallback or rewriting saved game data.
LibraryQuery is a pure stable projection over canonical import order. The coordinator stores that
canonical snapshot separately, remembers focused hash, and maps visible navigation back to canonical
positions for persistence/removal/Undo. Query writes persist independently. A null placeholder handles
empty results; allGames retains canonical entries for all-library scraping. Native/preview screens
freeze the projection, then refresh after ejection, so history updates cannot change an inserted identity.

DirectoryScanner queries DocumentsContract on IO, retaining URI grants. Positive matching provider
size/mtime plus a present same-sized private ROM permit an incremental skip; zero/null/unknown stamps
force import/hash. Verify all bypasses the optimization, addressing providers that reuse stamps.
Each success commits its hash/index; an incomplete/cancelled enumeration never marks unseen files
missing. Completed scans mark only missing indexed documents for that root. Duplicate sources may map
to the same game; source status reflects the last scans and is not a live file-existence guarantee.
Forgotten roots drop index/registration and release that grant, preserving membership/enrichment/caches.
No WorkManager/scheduled/background index or automatic collection deletion is introduced.

PlayClock uses a monotonic clock and engine Running boundaries. The coordinator's mutex serializes
history writes, periodic checkpoints and pause/eject flushes. Pending failed checkpoints are keyed by
hash and retry independently on later session writes. History errors remain separate from native save
warnings; the previous DB totals stay intact. Activity/process death may discard unsaved deltas.
MainActivity's metadata/search text overlays route input to Android/Compose; controller actions are
application-owned. LibraryPanels presents optional facts without requiring metadata for launch. Opaque
panels pause, rather than destroy/recreate, the existing Filament viewport. Picker add/recovery intent
flags survive Activity recreation; ROM results wait for a loaded/unlocked library before dispatch.

Storage/migration API references were rechecked against official Android documentation:
[SAF documents](https://developer.android.com/training/data-storage/shared/documents-files) and
[Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions).
Pinned dependencies and the native/3D surface strategy are unchanged.

## M3 reviewed enrichment

ScrapePreferences persists region/language separately from the saved batch. RetroBase carries actual
regional title and synopsis-language tags alongside values. Stable preference ranking keeps tags aligned,
flat labels before extra media and all alternatives available; unknown tags stay unknown. Journal v2
retains outcomes and tags while reading v1/untagged candidates. Room remains the accepted-data authority.
Selected/missing-label scopes snapshot canonical content IDs before network work. Only the current title
is searched; Next loads cached candidates or searches the next pending one. Resume refreshes current
accepted values, preventing stale bare journal game records from enabling silent replacement.

A chosen match opens Review. Preferred-label preview is guarded by existing-label protection; explicit
media selection retains replacement control. Fill missing metadata reads and merges in one Room
transaction so accepted manual/provider values always win. Room v6 stores one previous enrichment
snapshot per game before changed label/metadata/media writes; no-op writes retain the snapshot. Undo
restores only enrichment and consumes it, independently of sources, saves/history/favorites or membership.
Explicit 5→6 migration extends the full chain without destructive fallback.

A filtered-out preview target uses a transient canonical projection. It does not persist a query or
selection change. Apply/cancel/recreation therefore restore the original collection view/focus while
writes address the target hash. Opaque scrape pages pause Filament; genuine GLB preview resumes it.
Native/core/saves and dependencies remain unchanged. See ADR 0010 and M3_SCRAPE_REVIEW.

## Offline pack boundary

BoundedArchive streams ZIP into generated private filenames with compressed/expanded/entry limits;
archive names never become output paths. ScrapePackStore validates the v1 manifest, uniqueness, every
image SHA-256 and LabelStore format/dimension limits before promoting the bundle. Candidate sourceName
and original media URL are independent from the private cartridge-pack URL. PackAwareProvider handles
only validated private URLs; online calls still use the audited provider. Local journal entries cannot
fall back to network on missing candidate files/corrected searches. ScrapeCoordinator matches canonical
existing hashes and replaces queue progress only after a successful matching import; accepted Room data
is untouched until explicit review. Valid cancelled/unmatched bundles may retain unreferenced files.
No new library/native integration or change to GPU ownership; see ADR 0011.

## M4 layouts

ADR 0012 supersedes the earlier fixed three-instance description; ADR 0019 in turn replaces its
nine-instance pool and positional window with the stable fifteen-object pool described under Rendering. CollectionLayout owns spatial
movement and bounded windows over the same canonical/query projection. All layouts share the session
coordinator and nine-instance GLB pool: three active in Horizontal/Vertical, nine in Grid. Labels and
acknowledgements map through windowStart; the decode cache is bounded to twice the visible count.
Presentation preferences persist layout independently of Room focus/query. Grid uses three columns
and springs its row centre; view changes snap previous spring positions without changing game identity.
ControllerRouter routes two axes only to library movement; menus and gameplay retain their own paths.
Neutral stick/key release resets repetition before the next frame. GPU surface handoff is unchanged.

## M5 systems and cartridge memory

Room schema 7 and GameSystem carry GBA/GB/GBC identities through importer, cache extension, request, scraping and journal v3. The same nine-slot viewport owns GBA and tall instances (18 bounded meshes), draws one format per slot and decodes aspect-specific labels. GB/GBC use source-built mGBA with M_CORE_GB enabled. The original frontend wrapper synchronizes pending battery masks and live MBC3 RTC registers before bounded JNI SAVE_RAM/RTC reads. Restore happens before the first frame; user state load checkpoints restored memory immediately. See ADR 0013.

## M6 shell preferences

ConsolePreferences carries supported controller and core choices independently of Room identity. ControllerRouter detects physical shortcuts before gameplay remapping. Core variables initialize through GLRetroViewData and current updates use the session mutex/GL queue. ConsolePanel pauses collection rendering, never emulation behind an active panel. HomeRegistration only enables eligibility and creates the Android-owned role/settings intent; MainActivity is singleTask. Notice assets derive from versioned docs/licenses at build time. See ADR 0014.

## M6 session recovery

SessionStore publishes checksum/version-bound completed state generations separately from manual saves. LibretroEngine checkpoints state after battery/RTC and restores before the first actual core video frames; JNI video callback count supplements pinned lifecycle setup barriers. The coordinator serializes background checkpoint/stop/release and fresh-view paused restoration. Cold startup offers recovery from the collection. The AndroidView is keyed by GLRetroView identity, preventing a stopped Compose collector from reusing a released host. Actual presented pixels are retained as one optional pause bitmap while Android’s paused surface releases buffers. See ADR 0015.

M6 portable backup: logical records and declared checksummed blobs, read-only staging/review, append-only save merge and a Room transaction are specified in BACKUP_FORMAT and ADR 0016. No ROM bytes or Android grants/default roles are exported; settings restore is explicit.

The configured input threshold is applied before generic UI repeat timing, avoiding a second fixed threshold. Console list focus uses Compose bring-into-view. Portable state records are core-bound with or without an automatic session. Process/stability diagnostics run only in instrumentation against an isolated DB and generated original game; they are not normal product flows.

Resume-record IO finishes before the coordinator copies current state; a suspended receiver copy must not overwrite intervening render readiness. The scale regression recreates a 500-title grid eight times to cover this ordering.
