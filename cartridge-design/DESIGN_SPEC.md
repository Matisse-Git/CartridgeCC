# Cartridge — redesign spec

This folder is the complete redesign of the Cartridge Android launcher. `screens/` holds one HTML mockup per screen (open any of them in a browser source view — every exact size, color, radius and spacing value is in the inline styles; data and behaviour are in the `<script>` block at the bottom of each file). This document is the written intent behind them. Where the two disagree, this document wins.

Design target: landscape, 1280×720 reference canvas (scale with density), controller-first, touch as a fallback.

---

## 1. Principles

1. **Apple-like, dark, calm.** True black canvas. The UI itself is grayscale; color comes only from game content (box art, screenshots, video).
2. **No neon, no cyberpunk.** No glowing grids, synthwave gradients, saturated pinks/purples or arcade display fonts. Placeholder tones are muted and earthy.
3. **Console feel.** Every screen has a bottom hint bar of controller glyphs. The primary action is always **A** and is always the filled glyph. L1/R1 switch top-level tabs.
4. **Focus is unmistakable from across a room.** Focused items turn white (black text) or get a white ring, lift and scale up slightly.
5. **Immersion before launch.** The focused game's screenshots/video are visible behind the UI before the player presses Play.

## 2. Tokens

### Color
| Token | Value | Use |
|---|---|---|
| canvas | `#000000` | every screen background |
| surface-1 | `#141416` | cards at rest |
| surface-2 | `#1C1C1E` | grouped lists, settings groups |
| surface-3 | `#2C2C2E` | focused card, selected sidebar row |
| separator | `rgba(255,255,255,0.08)` | hairlines |
| glass | `rgba(28,28,30,0.72–0.78)` + background blur 40–50px + 1px `rgba(255,255,255,0.08)` border | pause panels, quick menu, pills over media |
| label | `#F5F5F7` | primary text, focus fill |
| secondary | `#A1A1A6` | metadata |
| tertiary | `#8E8E93` | captions, section headers (only on ≤ surface-2) |
| accent | `#0A84FF` | "listening"/remap state, toggled-on icons, links (`#409CFF` for text) |
| success | `#30D158` | switches on, battery, connected dot |
| destructive | `#FF453A` (text `#FF6961`) | Quit Game, Power off |
| favorite | `#FFD60A` | star |

### Type — Inter (system SF-like); tabular numerals for times/counts
| Style | Size / weight | Tracking |
|---|---|---|
| Display | 58–60 / 800 | −0.035em |
| Title | 26–30 / 700 | −0.02em |
| Headline | 17 / 700 | −0.01em |
| Body | 15 / 500 | 0 |
| Caption | 13 / 500 | 0 |
| Section label | 12 / 600, UPPERCASE | +0.04em |

### Shape & layout
- Radii: 8 · 12 · 16 · 22 · 28 · pill (999).
- Screen side margin 40px. Top bar 76px tall. Bottom hint bar 64px tall.
- Touch/focus targets ≥ 44px.

### Motion
| Interaction | Spec |
|---|---|
| Shelf slide (focus change) | 450ms, cubic-bezier(.2,.8,.2,1), on position, rotation and size |
| Focus lift | 250ms; scale 1.04–1.05 + shadow `0 12–24px 28–48px rgba(0,0,0,.6)` |
| Pressed | scale 0.97 |
| Pause open | 220ms; game behind blurs to 28px and dims to 55% brightness |
| Launch | selected box flies toward the camera, fades into the game |
| Background preview | screenshot first, crossfades to muted video after ~1.5s dwell |

### Controller glyphs
- **A**: 24px filled circle, `#F5F5F7` fill, black 12/800 letter.
- **B / X / Y / Menu / Home / R3**: 24px circle (R3 is a 30px pill), 1.5px `rgba(245,245,247,.55)` outline, white letter/icon.
- **L1 / R1**: 32×22 rounded rect, 1.5px outline at 35%, 10/700 `#A1A1A6`.
- Hint = glyph + 14px label, 8px gap, hints 24–26px apart, right-aligned in the hint bar. Left side of the hint bar holds context text in tertiary.

## 3. Global chrome

**Top bar (Library, Systems, Apps, Settings):** left = cartridge logo + "Cartridge" 17/700; center = L1 · pill segmented tabs (Library / Systems / Apps / Settings; selected tab = white pill with black text) · R1; right = Wi-Fi icon, battery % + icon, clock.

**Bottom hint bar:** see glyphs above; contents change per screen and per focused item.

## 4. Screens

### 4.1 Library — 3D shelf (`Main.dc.html`, home screen)
- **Background preview:** the focused game's screenshot/muted gameplay video fills the top 540px at 70% opacity, under a vertical gradient (78% black at top for the top bar → clear → black by y≈540). Changes when focus changes. A glass pill top-right says "Gameplay preview" with a muted-speaker icon and a thin progress bar. **R3** toggles preview sound. User setting to turn the preview off / change its strength.
- **Filter chips** under the top bar: Recently Played (selected), Favorites, All Games, then one per system with counts. Sort control on the right.
- **Shelf:** real 3D boxes (front cover, left spine with vertical title, right edge, top face), bottom-aligned on a baseline, with a faint floor reflection.
  - Box aspect follows the real packaging per system: PS2 176×248, GBA 190×211, SNES (US) 240×172, PS1 jewel case 204×180 (base size in px at 1280 width).
  - Focused box: centered, scale 1.24, rotateY +16° (spine visible), translateZ 30, specular sheen, drop shadow.
  - Neighbours: rotateY ±50° facing the center (tweakable 20–75°), centers at 260px then +150px each step from screen center, darkened 30% (1st) / 50% (2nd+), 3rd neighbour at 55% opacity, further ones hidden. Perspective ≈1100px.
  - D-pad left/right moves focus; touch: tap a side box to focus it.
- **Info under the shelf (centered):** full title 30/700; "System · Year · Genre" 15 secondary; "Last played … · Xh Ym played · N save states" 13 tertiary.
- **Hint bar:** left "3 of 10 · Recently Played"; right A Play, X Details, Y Favorite, R3 Sound, Menu Quick menu.

### 4.2 Systems (`Systems.dc.html`)
- Header "Systems" + "10 systems · 1,284 games", "Sorted by most played".
- 5×2 grid of cards (22px radius). Card: maker · year, system name 20/700, **spine art row**, game count 26/700 + "games", default core name.
- **Spine art:** up to 9 spines, 17px wide × 82px, 3px gap, real spine art when available, otherwise muted tone with the title running vertically (8/700 white, ellipsized). Order: the system's **most recently played games first (up to 3) at full opacity**, then a 6px gap, then the **rest of the library A–Z at 62% opacity**. A game's tone matches its box tone on the shelf.
- Focused card: surface-3, scale 1.05, 2px white ring, big shadow.
- Hints: A Browse games, Y Choose core, B Back.

### 4.3 Game detail (`GameDetail.dc.html`, X from the shelf)
- **Hero:** the game's trailer plays muted full-bleed (80% opacity) under a left-to-right black gradient (92% → 25%) and a bottom fade to black. Glass pill top-right: ▶ Trailer, progress bar, 0:42 / 1:58, muted icon. R3 = trailer sound.
- Left: large 3D box (236×262 for GBA), rotateY 24°, with reflection.
- Right column: chips (system, year, ★ Favorite), series eyebrow 16/600 secondary ("The Legend of Zelda"), title 58/800 ("The Minish Cap"), "Genre · players · developer / publisher", 2-line description.
- Actions: **Resume** (white pill, 56px, black A glyph, subtitle "Slot 2 · Minish Woods · 2 h ago"), Start over, favorite star button, more (…) button.
- Stats row: Played, Last played, Save states, Core.
- **"Videos & screenshots" strip** along the bottom: trailer thumbnail first (play badge + duration), then screenshots, 150×100 at native aspect, 12px radius; focused thumb gets a 3px white ring.
- Hints: A Resume, R3 Trailer sound, Y Favorite, X Game settings, B Back.

### 4.4 Launching (`Launch.dc.html`)
- Game scenery/screenshot at 50% behind a radial vignette; the box centered and tilted slightly toward the viewer; series eyebrow, title 28/700, "Restoring Slot 2 · Minish Woods · mGBA", 220×4 progress bar. Bottom glass pill: "Press Home during play to open the pause menu".

### 4.5 In-game pause menu (native overlay; `Pause.dc.html`, `PauseInput.dc.html`, `PauseCore.dc.html`, sidebar = `PauseMenu.dc.html`)
- Opened with **Home** (configurable). Emulation pauses; the game frame stays visible at its native aspect (e.g. 3:2 GBA pillarboxed), blurred 28px, 55% brightness, plus a 25% black wash.
- **Left sidebar** (300px, glass, 28 radius): game header (mini cover, series eyebrow, title, "System · core · session time"); four round quick actions (Rewind, Fast 2×, Capture, Mute); then menu rows (44px, 12 radius, icon + label + value): Resume, Save States, Controls, Core Settings, Display & Shaders, Cheats, Reset Game, **Quit Game** (red). Selected row = white fill, black text.
- **Right panel** (glass) shows the selected section. Hint bar sits below it.
- **Save States:** "N of 10 slots used · auto-saves every 5 minutes", "Auto-save on quit" switch. 3-column grid of slots at the system's native aspect, each with a screenshot thumbnail, "Slot · in-game location" and timestamp; auto-save badged AUTO; first tile = dashed "New save state". Focused slot: 3px white ring, scale 1.04. Hints: A Load, X Overwrite, Y Delete, B Resume.
- **Controls:** player tabs; connected controller with battery; a diagram of **the emulated system's** button layout (GBA: D-pad, A, B, L, R, Start, Select); mapping list "Game button → Your controller". Remapping row turns accent blue ("Press a button…") and the matching button glows in the diagram. Toggles: Left stick moves D-Pad, Turbo. Changes are per game; Y saves as the system default.
- **Core Settings:** segmented "This game / All <system> games"; core card (name, version, one-line description, Change core); grouped settings (Performance: Run-ahead stepper, Frameskip, Idle loop removal; Emulation: Color correction, BIOS, Interframe blending switch); Display card with a **live preview** of the current frame with the chosen shader (LCD Grid), aspect ratio, integer scaling. Settings are per-core and come from the core's options.

### 4.6 Quick menu (`QuickMenu.dc.html`, Home button outside a game)
- Right-side glass panel over the dimmed, blurred launcher: big clock + date, battery % with time left and a green bar, 2×2 tiles (Wi-Fi, Bluetooth/controllers, Do Not Disturb, Record clip), brightness and volume capsule sliders, Performance segmented control (Quiet / Balanced / Boost), Sleep / Restart / Power off.

### 4.7 Apps (`Apps.dc.html`)
- "Apps · N installed", search field (Y focuses search). "Pinned" row of 84px icons, then an "All apps" grid of 72px icons, 8 per row. Use the real installed Android app icons and labels. Focused icon: scale 1.14, lift, 3px white ring, label becomes a white pill. Apps launch full screen; Home returns to Cartridge. Hints: A Open, X Unpin, Menu App info, B Back.

### 4.8 Settings (`Settings.dc.html`)
- Left section list with colored icon tiles: Library, Systems & Cores, Controllers, Display, Appearance, Sound, Storage, Network, Android, About.
- Library section: Game folders (path, system, game count; Add folder…), Scanning (Scan now + last scan; scan on start switch), Box art (live 3D box preview, Show boxes in 3D, Download missing art, Art source).

## 5. Content & media rules
- All box art, spines, screenshots and videos come from the app's real scraped/local media.
- When box art is missing, render the **placeholder box**: muted two-tone gradient, faint top highlight, system + year band, faint image icon, series line + title at the bottom. Never invent cover art.
- When no screenshot/video exists, fall back to a blurred, darkened enlargement of the box art, then to plain black.
- Videos always start muted.
- Save-state thumbnails are captured frames at the system's native aspect.

## 6. Out of scope / keep as-is
- Emulator cores, ROM scanning, save-state logic, input handling and Android launcher plumbing already exist — restyle and restructure the UI around them; don't rewrite them.
