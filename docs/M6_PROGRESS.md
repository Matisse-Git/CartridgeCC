# M6 — Console environment

Active, proceeding through focused increments after M5. This is not a public release.

## Setup, preferences and optional HOME — version 13

First launch of an empty collection offers a controller-friendly setup with folder selection,
controller settings and Continue. Existing collections are migrated as set up; setup remains
available in Console settings. B → Collection → Console settings and Tools → Settings share one
panel. Paused gameplay offers Game settings and Controller alongside resume/save/load/eject.

Menu A/B and gameplay A/B are separate persisted preferences. Gameplay shoulder swap, stick dead
zone (20/35/50%) and the two existing physical pause combinations are supported. Core settings wrap
the inspected mGBA 0.10.5 audio filter and frame-skip values in app-owned labels. Current paused core
updates are serialized on its GL thread; startup passes the same saved options through pinned
GLRetroViewData.variables. No hardware HOME/power interception or arbitrary unsupported options.

The exported HOME activity alias starts disabled. The user can enable it as a candidate and request
Android's ROLE_HOME chooser (API 29+) or Home settings fallback. Android owns the default choice;
ordinary app installation does not change it. The Activity is singleTask to avoid duplicate sessions.
About & licences presents matching checked-in notice files packaged reproducibly from docs/licenses.
It does not resolve the remaining complete-source/public-release licensing gate.

Actual RP5 Android 13 results, 2026-10-10: both APKs build, 42 host tests and lint pass. Two new device
tests pass in 6.381 s. They verify first-run completion/recreation, separate A/B choices through real
GB input/battery bytes, persisted dead zone/shortcut, actual core option values/current paused update,
notice availability and disabled/enabled HOME resolution without changing the owner's default role.
The real Android default-home selection/reboot remains an explicit user-choice test, not automated.

Sleep/recovery, backup/restore and sustained stability are the next increments; M6 is not yet complete.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
.\tools\Test-Device.ps1 -Serial <authorized-device> -Suite Environment
```

Platform references inspected 2026-10-10:
[RoleManager](https://developer.android.com/reference/android/app/role/RoleManager),
[Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle).
