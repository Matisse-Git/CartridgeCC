# ADR 0021 — the Android apps beside the library

Date: 2026-10-11. Status: accepted by the owner's direction. Extends ADR 0018 (one navigation owner)
and ADR 0014 (optional HOME).

## Context

The owner uses Cartridge as the front of a Retroid Pocket 5, sometimes as its Android HOME app, and
could not reach anything else installed on the device without leaving it or changing launchers. The
request: press B on the game library, find the app, press A. No permanent button, no second
navigation system, no apps dressed up as cartridges.

## Decision

**B at the library's root opens Apps; everywhere else B is still Back.** `ConsoleShell.back()`
already resolves the back role from most specific to least: the artwork workflow, a settings pane,
a running import, any open destination, the label preview, a paused game, a library search. One
branch was added after all of them: open Apps. `openApps()` itself requires an empty destination
stack, `InputMode.Library` and no search, so it cannot fire over a sheet, a page, a game, a closed
screen or a running operation, whoever calls it. A running game never asks the shell: in
`InputMode.Gameplay` the router hands B to the core, as before.

**Android's Back is a separate role.** `ControllerRouter` used to report B, `KEYCODE_BACK` and
Escape as one `back`. It now reports the last two as `systemBack` (defaulting to `back` for callers
that do not care). `ConsoleShell.systemBack()` closes everything B closes, Apps included, and at the
library's root does nothing, exactly as before. A launcher must not walk away from its home page on
Android's Back, and the request was for the controller's button only.

**Apps is one opaque destination on the existing stack.** `Destination.Apps` is pushed and popped
like Settings. The coordinator is not told: shelf, focused game, selection and the 3D stage are not
touched, Filament is paused beneath it as under any opaque page and resumes when it is popped. The
confirm/back swap in Settings › Controls moves the way in with it, because it moves the role.

**Apps are not games.** `apps/AppLibrary` holds the list, the search text and the focused app. Nothing
is written to Room, there is no console record, no `LibraryGame`, no label and no 3D object. A tile
is the app's own launcher icon and name on the console's own ground, with the console's own focus
treatment (raised fill, accent mark, name at full strength).

**Android is asked through `LauncherApps`, with the narrowest visibility that works.**

- List: `LauncherApps.getActivityList(null, myUserHandle())`, kept only where the component is also
  returned by `PackageManager.queryIntentActivities(MAIN + LAUNCHER)`. The second call exists
  because, for an installed app with no launcher activity, Android can add a stand-in entry that
  only opens that app's App info page; those are not apps to start. Cartridge's own package is
  dropped. One entry per component; an app with two launcher activities has two.
- Icon and name: `LauncherActivityInfo.getIcon(density)` and `.label`, read off the main thread, the
  icon at the density whose 48 dp is at least the size drawn. Adaptive icons are drawn with the
  device's own mask.
- Start: `LauncherApps.startMainActivity(component, user, null, options)`. Android checks again that
  the component is an exported launcher activity before starting it, so the console can only start
  what a home screen could. No intent is built by hand.
- Changes: `LauncherApps.Callback`, registered when Apps is first opened and removed when the
  Activity is destroyed; plus one re-read each time the console returns to the foreground, once
  Apps has been shown. No polling. The callback is held by an application-context service, so it is
  always unregistered in `ConsoleShell.close()`.
- Visibility: the manifest declares
  `<queries><intent>` MAIN + LAUNCHER `</intent></queries>`. That makes visible exactly the apps
  that have a launcher entry. `QUERY_ALL_PACKAGES` is not requested: on the RP5 the console lists
  38 entries and `adb shell cmd package query-activities`, which is not filtered, lists the same 38.

**Order and search.** `java.text.Collator` for the device locale, with the component id settling
apps that share a name, so the order is the same on every read. Search is the console's existing
text entry on Y; names are matched without case or accents, because the console's own keyboard has
neither. B clears a search before it leaves, as it does in the library.

**Focus survives.** The focused app is addressed by `package/activity`, never by position. It is
kept while the list is re-read; an app that went away hands focus to whatever stands in its place.
It is written to preferences (`appsFocus`) when an app is started, when Apps is left and when the
console leaves the foreground, and `appsOpen` is saved in the Activity's instance state, so a
console Android rebuilt while an app was in front comes back to Apps on the same app.

**Starting an app is light.** No insertion, no closed screen. The app arrives with the console's own
enter motion (220 ms, the same curve, as `res/anim/app_open`), or as a cut under reduced motion.
The console is not brought forward again by anything it does; the player returns with Android's own
Back or Home.

**The hint.** `B Apps` joins the library's hints, before `START Menu`, and is the touch control for
the same thing. It follows Settings › Appearance › Button hints, but under *Until I know them* it
retires on its own count (five openings of Apps): an owner who learned the other hints long ago
would otherwise never be told. There is no other button, tab or menu row.

## Consequences

- One new manifest element, no new permission, no new dependency, no Room change.
- `KEYCODE_BACK` and Escape no longer reach `ConsoleShell.back()`. Anything that should react to
  both must be reachable from `dismiss`, which both call.
- Apps lists whatever has a launcher entry, including other emulators and front ends. Starting one
  is the player starting an Android app. It is not Cartridge playing a game with it: games are
  still only ever run by the embedded cores (README, AGENTS).
- Screenshots and interface tests use invented apps (`FixtureAppSource`), so nothing committed
  shows what the owner has installed. One device test uses the real service and records counts only.
- With `<queries>` in place, other packages' launcher activities become visible to
  `PackageManager` calls made anywhere in the app. Nothing else in the app enumerates packages.

## Not decided here

App info, uninstalling, hiding or pinning apps, favourites, recently used, folders, a work profile's
apps, and widgets. Any of them would be a management feature with its own stored state; this
increment discovers, shows, searches and starts.
