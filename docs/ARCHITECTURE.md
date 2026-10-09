# Architecture

One Android module keeps the feasibility work reviewable. Packages separate pure navigation/session
logic, input routing, file handling, renderer ownership and the emulator adapter. MainActivity owns
Android views/lifecycle; an Android-free CollectionCoordinator owns selection, operations and handoff
decisions. Native objects are never retained in a ViewModel or global UI singleton. RoomCollectionRepository
persists the three slots, ROM identities/source URIs, focus and accepted label recipes. Schema JSON is
versioned under app/schemas; no destructive migration fallback is permitted.

## State and input

`SessionMachine` enforces Idle → Preparing → Running ⇄ Paused → Stopping → Idle, with Error exits.
`LibretroEngine` exposes Flow status and serializes native operations with a Mutex. Preparing includes
core/game setup and waits for the first real FrameRendered event. Error recovery destroys the native
session and returns through cleanup to Idle before retry. A process-wide atomic claim prevents a second
LibretroDroid native frontend from being initialized concurrently.

The coordinator independently tracks Library, Inserting, Handoff, Emulator, Ejecting and LabelPreview,
selection, viewport generation and busy state. The
busy flag locks input during preparation/save/ejection. It does not declare an emulator Ready state.
`ControllerRouter` sends each event to exactly one route: Library, Gameplay, Menu or Locked. Android
keys go to GBA port zero; sticks/HAT use a digital D-pad mapping with a 0.35 dead zone. Navigation is
polled by an Activity Choreographer callback: first action immediately, first repeat at 300 ms,
following repeats at 110 ms. X invokes the game picker, Y opens scraping and Select invokes the image picker in Library; label preview routes
D-pad/A/B to its own actions. Gameplay never receives those UI actions.
Pause clears all previously forwarded keys/axes. No HOME interception or system-button assumptions.

## Graphics ownership

Filament uses a SurfaceView/UiHelper swapchain. Its glTF loader creates three instances sharing meshes,
shell textures/materials. CartridgeLabels duplicates only the named label material per visible instance
and owns its replacement SRGB8_A8 texture. GLB/image file reads and label decoding use IO; every Filament/JNI resource call stays on Main. Texture
resources load asynchronously and renderables are added when ready. A Choreographer callback updates
root transforms and draws; the focus spring retargets in place. Backgrounding pauses that loop.

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
Pause uses the registry to stop the render loop/audio. Save calls queue a coroutine result on the GL
thread, invoke the upstream serialization methods with `useEmulationThread=false`, catch exceptions
there, and time out after three seconds. This avoids the upstream latch hanging on a thrown native error.
Timeout recovery stops the GL thread before destroying native state; no concurrent save/destroy is allowed.

Files use a stable SHA-256 ROM-content identifier. SAF document import enforces `.gba`, size 192 bytes
through 32 MiB, and bounded streaming into a private working copy. Save files are `<hash>.sram` and
`<hash>.state`; invalid identifiers are rejected. Writes sync a temporary file then atomically replace
the previous file. Save-state core-version compatibility is not encoded yet; mGBA is pinned for M0.

## Lifecycle limits

Activity focus loss/disconnection clears UI input and pauses gameplay. On Activity pause the coordinator
cancels an unfinished insertion, waits for in-flight save/import writes, then ends any session after RAM
persistence. Cancellation cleanup uses NonCancellable while the Activity remains alive. On destruction an
idempotent synchronous native release is the final safety barrier. It cannot promise saving after
process kill, and full-state restoration after rotation/suspend is deferred. Room restores assignments,
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
entries can retry without repeating successes. The queue is in memory and dies on recreation; Room
selections persist. One-title/GBA/all scopes exist for three slots, not a directory-scale scanner.
Durable jobs, selected subsets/consoles, fill-missing automation, scrape packs and undo remain planned.
See LABEL_SCRAPING and ADR 0005. Renderer/native surface ownership is unchanged by the scraper.
