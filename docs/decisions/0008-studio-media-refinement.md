# 0008 — Original media, offline studio lighting and measured presentation

Accepted 2026-10-09. The owner explicitly requested M2 now; M1's outstanding long-duration and
sleep/reboot validation is retained rather than silently marked complete.

Keep direct Filament 1.72.1 and exclusive GPU ownership. Refine the reproducible Python GLB generator
while Blender is unavailable: smooth segmented bevels, a genuinely recessed rounded label, shell seam,
grip ribs and separate contact plating. Four cartridge materials and about 6,900 triangles fit the
initial budget. Keep exactly one named replaceable label primitive and the existing top-left UV contract.
Provide original micro-normal and linear roughness textures with explicit tangent vectors; no scratches,
commercial artwork or branded moulding. Manual Blender art direction remains available for later polish.

Use original analytic HDR softboxes, prefiltered **offline** by matching cmgen 1.72.1. Pin
filament-utils-android 1.72.1 for its official KTX1Loader, avoiding a custom KTX/JNI parser. The loader's
bundle owns both indirect light and cubemap; explicitly destroy both. Read cartridge/receiver/KTX bytes
on IO once per Activity, reuse them across viewport recreation, and keep GPU creation/destruction on Main.
No runtime environment prefilter, skybox photograph, bloom or new context sharing.

A separate small GLB receiving slot is temporarily visible during insertion/ejection. Its front lip
physically occludes the descending cartridge. One ResourceLoader per asset keeps asynchronous ownership
explicit. Three cartridge instances remain the library pool regardless of collection length.

Centralize 540/420 ms timings and normalized lift/align/slide poses in MediaMotion. Use a linear clock
and separately eased physical phases, reversed for ejection. Motion off bypasses insertion/ejection
and snaps the focus spring, resetting velocity. One original optional SoundPool contact is off by
default and released with the Activity; it does not enter emulator audio. Put touch tools on a temporary
shelf to keep the collection quiet. Controller shortcuts and scraping choice semantics are unchanged.

Measure actual SurfaceView presentation with SurfaceFlinger timestamps, not Compose FPS or only
Choreographer callbacks. A debug-only isolated original-artwork viewport records rest, rapid navigation
and insertion/ejection; CPU submission metrics are separately labelled. Require real samples and p95
within 1.5 device refresh periods. This short gate does not establish sustained thermal/memory stability,
input latency, battery drain or all artwork/library workloads.

Primary source inspected at the pinned tag: [KTX1Loader](https://github.com/google/filament/blob/v1.72.1/android/filament-utils-android/src/main/java/com/google/android/filament/utils/KTX1Loader.kt),
[utils initialization](https://github.com/google/filament/blob/v1.72.1/android/filament-utils-android/src/main/java/com/google/android/filament/utils/Utils.kt),
[cmgen](https://github.com/google/filament/blob/v1.72.1/tools/cmgen/src/cmgen.cpp),
[IndirectLight](https://github.com/google/filament/blob/v1.72.1/android/filament-android/src/main/java/com/google/android/filament/IndirectLight.java),
and [Android 13 UiAutomation argv execution](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-13.0.0_r1/core/java/android/app/UiAutomationConnection.java).
