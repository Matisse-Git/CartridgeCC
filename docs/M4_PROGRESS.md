# M4 — Shared horizontal, vertical and grid views

Version 11, `0.4.0-layouts`. Open B → Collection → View to cycle layouts. The choice survives reopening;
the currently focused game stays selected. D-pad/left stick use left/right in Horizontal, up/down in
Vertical and both axes in the three-column Grid. Grid clamps at row/collection edges; a partial final
row selects its last available game. Menus use their existing vertical controls. Touch arrows still
advance sequentially and the View action is touch-accessible.

All views use CollectionCoordinator, canonical Room identities/query/history, the same original GLB,
per-game labels, draft preview and insertion/emulation/ejection. The focus spring retargets immediately;
grid additionally springs the viewport row centre. Reduced motion snaps both. The selected object
enlarges and aligns with the existing slot during insertion. One nine-instance mesh pool is independent
of library length: three active objects for carousels and up to nine for grid. Label windows/caches are
bounded to 3/6 or 9/18; unused GPU instances remain outside the far clip.

Files: CollectionLayout, CollectionCoordinator, ControllerRouter, MainActivity, CartridgeSurface,
CartridgeLabels, debug preference/profile fixtures, layout host/device tests, Test-Device and ADR 0012.
Dependencies, Room schema and emulation ABI are unchanged; no new third-party assets or libraries.

Host tests cover spatial/clamped movement and every bounded window for counts 1–1,001, identity
preservation, persistent canonical focus and label acknowledgements beyond the first window. RP5 tests
exercise twelve original homebrew identities, all layouts, D-pad, synthetic joystick dead zone/hold/
reversal, recreation, real label preview, internal mGBA, pause/eject and real SurfaceView pixels. Another
test navigates 500 original metadata records through 240 rapid moves, keeps nine mesh instances and
bounded labels, and reopens the persisted grid. It does not establish performance for 500 distinct
artworks or prove a long-term memory plateau.

Required Gradle build/test APK/lint passed: 37 host tests, eight assets, lint 0 errors/24 warnings.
The final full RP5 suite passed 23/23 in 138.594 s; an earlier native 50-handoff assertion passed.
TESTING records actual short presentation samples and corrected input/menu failures. Owner physical stick
and layout/art-direction review, partial rows under filters/sort, many distinct label textures,
sustained grid navigation, thermal/battery/input latency and sleep/reboot remain shared physical gates.
M5 adds audited GB/GBC media/emulation next; M6 console features follow under the owner's authorization.
