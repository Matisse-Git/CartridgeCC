# ADR 0012 — Layouts over the shared collection window

Accepted 2026-10-10.

Horizontal, vertical and grid are projections of the same Room-backed canonical game identities,
query, focus and session coordinator. CollectionLayout defines spatial movement and a bounded window;
the controller supplies two axes to that projection. Grid uses three columns, keeps column position
on vertical moves where possible and clamps at edges without horizontal row wrapping. Menus retain
their independent vertical input. Neutral key/stick releases reset repetition immediately.

One Filament viewport uses nine instances of the shared GLB. Horizontal/vertical expose three;
grid exposes at most nine. Unused instances stay beyond the far clip. Labels and GPU acknowledgements
use the same global window offset as collection state. The decoded-label cache holds at most twice
the visible count. No mesh or texture pool grows with library length.

Persist the layout as an Activity presentation preference independently of Room sorting/filtering.
Changing it preserves the focused hash, resets input and snaps the old spring pose. Normal navigation
retargets the current spring; grid scrolls row centre with the same spring implementation. Insertion
aligns the selected cell to the existing receiving slot and uses the unchanged serialized GPU handoff.
Touch arrows provide sequential fallback navigation; spatial touch hit testing remains later polish.

This retains direct Filament and separate emulator surfaces. It avoids three competing library systems
or native sessions. Short RP5 tests and presentation profiling verify this increment; sustained memory,
thermal/input measurements and owner art-direction review remain required.
