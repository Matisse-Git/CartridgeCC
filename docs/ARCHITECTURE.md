# Architecture

One Android module keeps the feasibility work reviewable. Packages separate pure navigation/session
logic, input routing, file handling, renderer ownership and the emulator adapter. The Activity owns
the shell and renderer handoff; native objects are never retained in a ViewModel or global UI singleton.
Future library persistence will use Room behind a repository; M0's three slots are explicitly transient.

## State and input

`SessionMachine` enforces Idle → Preparing → Running ⇄ Paused → Stopping → Idle, with Error exits.
`LibretroEngine` exposes Flow status and serializes native operations with a Mutex. Preparing includes
core/game setup and waits for the first real FrameRendered event. Error recovery destroys the native
session and returns through cleanup to Idle before retry. A process-wide atomic claim prevents a second
LibretroDroid native frontend from being initialized concurrently.

The shell independently tracks Library, Lifting, Handoff and Emulator, selection and busy state. The
busy flag locks input during preparation/save/ejection. It does not declare an emulator Ready state.
`ControllerRouter` sends each event to exactly one route: Library, Gameplay, Menu or Locked. Android
keys go to GBA port zero; sticks/HAT use a digital D-pad mapping with a 0.35 dead zone. Navigation is
polled every UI frame: first action immediately, first repeat at 300 ms, following repeats at 110 ms.
Pause clears all previously forwarded keys/axes. No HOME interception or system-button assumptions.

## Graphics ownership

Filament uses a SurfaceView/UiHelper swapchain. Its glTF loader creates three instances sharing meshes,
textures and materials. GLB file reads use IO; every Filament/JNI resource call stays on Main. Texture
resources load asynchronously and renderables are added when ready. A Choreographer callback updates
root transforms and draws; the focus spring retargets in place. Backgrounding pauses that loop.

On insertion, the shell closes Filament: remove the frame callback, detach UiHelper, destroy swapchain
and flush, cancel asset loading, destroy asset/material/light/camera/view/scene/engine resources. Only
after the completion barrier does it create LibretroDroid's separate EGL/GLSurfaceView. There is no GPU
context sharing or active 3D rendering behind gameplay. Compose draws ordinary UI above the active view.

Ejection pauses the GL thread/audio, captures SaveRAM on the emulator GL queue, writes on IO, destroys
the native frontend, removes the GLSurfaceView and waits for detach. Filament then recreates with the
existing selected index. Explicit host removal also completes while backgrounded; cleanup does not
depend on a future Compose animation frame. Close methods are idempotent.

TextureView compositing is deferred. For M0, separate SurfaceViews are the smaller correctness test.
Verify pause overlay z-order and aspect ratio on RP5; neither context sharing nor crossfade correctness
is assumed. Native context recreation in upstream GLRetroView is a known inspection risk.

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

Activity focus loss/disconnection clears UI input and pauses gameplay. On Activity pause the shell
ends any session after RAM persistence, removing both emulator/native resources. On destruction an
idempotent synchronous native release is the final safety barrier. It cannot promise saving after
process kill, and full-state restoration after rotation/suspend is deferred. Slot assignments do not
survive recreation in M0. These limitations are visible and require no fabricated gameplay fallback.

ADRs record dependency, surface and storage decisions. M1 should extract the shell coordinator from
the Activity into a tested owner without retaining Android rendering objects, after device feasibility.
