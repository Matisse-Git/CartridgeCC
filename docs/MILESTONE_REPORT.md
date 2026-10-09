# Implementation report through M6

Project Cartridge now has a native development implementation through the final roadmap milestone. GBA, GB and GBC emulation stays inside the app. Physical media are genuine GLBs rendered by direct Filament; gameplay owns a separate LibretroDroid surface and the previous GPU owner is fully released. No external emulator or browser renderer is used.

Implemented increments:

- M3: multiple SAF roots, incremental/full verification, hash-based deduplication and removal recovery; metadata/details/favorites/search/sort/history; API single-title/console/all/selected/missing-label scraping with independent choices, saved ranking, protected fill-missing, undo and offline reviewed packs.
- M4: shared horizontal/vertical/grid layouts and one bounded selection/renderer pool.
- M5: audited source-built mGBA GB/GBC support, explicit game/platform identities, a reusable tall cartridge model, matching label dimensions/provider routes, battery/RTC/state persistence and actual native output/input tests. Other consoles and optical media are future increments.
- M6: first-run setup, separate controller mappings/dead zone, wrapped supported core options, optional Android HOME registration/chooser, notices and Android settings access; completed-session checkpoints, actual frozen gameplay frames, fresh GPU surface recovery, and reviewed portable backup/restore.

Critical decisions: one serialized native session; no shared Filament/emulator context or active 3D loop behind gameplay; readiness from real native video callbacks; full states and battery files captured on the core's GL queue and written off Main; checkpoint manifest published last with identity/revision/length/hash checks; backup logical Room records rather than live SQLite/WAL copies, bounded generated staging and append-only save merges. Existing artwork/metadata/saves remain protected. ROMs, BIOS, grants, roles and credentials stay outside backups. Android chooses the HOME role only after the owner's explicit system choice.

Major file groups (paths relative to repository):

| Responsibility | Created/modified files |
| --- | --- |
| Library and accepted choices | `library/LibraryDatabase.kt`, `RoomCollectionRepository.kt`, `LibraryBackup.kt`; `scraping/` coordinator/provider/preferences/journal/pack classes |
| Android shell and state | `MainActivity.kt`, `session/CollectionCoordinator.kt`, `environment/ConsolePanel.kt`, `ConsolePreferences.kt`, `HomeRegistration.kt`, manifest and build configuration |
| Native play/recovery | `emulation/LibretroEngine.kt`, `EmulationEngine.kt`, `SaveBridge.kt`; `cpp/mgba_frontend.c.in`, `save_bridge.c`; `storage/SessionStore.kt`, `SaveStore.kt` |
| Backup | `storage/BackupStore.kt`, `BackupPreferences.kt`, `BoundedArchive.kt`, `LabelStore.kt` |
| Physical media | Versioned GLB/material/lighting generators and assets; `rendering/` bounded pool/labels/layout integration |
| Verification | Host/domain/store tests; RP5 `ConsoleEnvironmentTest`, `SessionRecoveryTest`, `BackupDeviceTest`, `ProcessRecoveryTest`, `ConsoleStabilityTest`; `Test-Device.ps1`, `Test-ProcessRecovery.ps1`, `Test-Stability.ps1` |
| Documentation | README, AGENTS, PRODUCT, ARCHITECTURE, ROADMAP, TESTING, LICENSING; M3/M4/M5/M6 progress; BACKUP_FORMAT, CONSOLE_RELIABILITY and ADRs 0009–0016 |

Java/Kotlin package paths above are under `app/src/main/java/dev/cartridge`; device tests are under `app/src/androidTest/java/dev/cartridge` and command helpers under `tools`. Small local commits preserve each increment. No remote push or public release was performed.

Build/test evidence and reproducible commands are in TESTING and CONSOLE_RELIABILITY. Latest host validation: both APKs, 44 unit tests, lint, ten asset checks and three GLB validators with zero errors/warnings. M0 acceptance passed on the physical RP5. The owner already confirmed physical navigation, GBA gameplay/audio, pause/eject, save/load and accepted label persistence. Final RP5 results: full 32-test regression passes in 189.118 seconds with 50 native handoffs and eight 500-title recreations; process-death and real reboot verification pass (2.443 / 3.346 seconds); ten-minute diagnostic passes (636.783 seconds), with 60.003–60.009 native frames/s and natural PSS 242.7–294.8 MiB. See M6_PROGRESS/CONSOLE_RELIABILITY for scope and limits.

Limitations: the original models still need owner/art polish review; label provider coverage and exact Socket parity are unverified; cores/peripherals beyond the verified GBA/GB/GBC scope are not promised; sudden death can lose progress since the last completed checkpoint. Portable states reject incompatible integration revisions. Backups are private and unencrypted, and ROM-folder permissions require reauthorization after transfer. Short diagnostics cannot establish multi-hour battery life or universal stability.

Remaining physical/release gates: choose/revert HOME through Android; larger mixed-title/distinct-artwork libraries, external controller disconnect/reconnect, revoked grants, full storage/provider-write failure and multi-hour unplugged heat/audio/latency measurements. Public distribution requires a GPL-compatible license/source/BOM plan and resolved provider/image terms. These gates do not hide incomplete emulation behind mock screens.

Recommended next engineering task: owner art/input review and a multi-hour mixed-title reliability profile, then one separately audited disc-system/media increment using the existing scraper review architecture.

Final build live provider check passes for GBA regional labels and GB/GBC platform/flat-label URLs (3.794 seconds), without downloading images or ROMs. M0 acceptance passed; M1–M6 implementations and their bounded hardware evidence are complete as specified above, while manual and public-release gates remain explicit.
