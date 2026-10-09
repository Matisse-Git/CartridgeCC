# ADR 0001: Direct Filament for M0

Status: accepted for feasibility, 2026-10-09.

The scene needs three instances of one glTF model with explicit context teardown. SceneView v4.53.0
was inspected and offers a current Compose DSL; it also brings a newer/larger toolchain dependency
set. Use Filament/gltfio 1.72.1 directly, following the pinned upstream Android lifecycle sample.
This keeps surface/resource ownership visible and compiles with the selected conservative shell.

Cost: we own Choreographer, swapchain, camera and entity cleanup code. Reconsider SceneView after
device proof if its lifecycle convenience demonstrably reduces this maintenance. No assertion that
SceneView is broken or slow is made without measurement.
