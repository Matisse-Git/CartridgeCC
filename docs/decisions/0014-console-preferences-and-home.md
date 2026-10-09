# ADR 0014 — Optional console shell using supported Android roles

Accepted, 2026-10-10.

Keep ordinary launcher entry and an initially disabled HOME alias to the same singleTask Activity.
Enabling eligibility is separate from asking Android to select a default. Use RoleManager on supported
versions and the platform Home settings fallback. Do not seize reserved buttons, replace firmware or
change the owner's default during tests. There remains exactly one native frontend/session claim.

One app-owned settings panel supports first-run and later configuration, with controller navigation
and touch actions. Persist only supported options. Separate UI A/B from gameplay A/B and shoulder
mapping; detect pause combinations on physical buttons before gameplay remapping. Limited friendly
audio/frame choices use source-inspected mGBA keys and serialized GL updates. This deliberately
avoids dumping core internals into the product interface or inventing unsupported runtime controls.

Package matching repository notice files through a build Sync task and expose them in-app. Preserve
release licensing/source obligations independently; a notices screen alone is not a compliant release.
