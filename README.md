# TapMuncher

A **neon maze-muncher remix** for the RayNeo X3 Pro AR glasses. The classic
bones — pellets, four power pills, four pursuers with distinct brains,
scatter/chase waves, fruit, a wrap tunnel — drawn as glowing vector lines on
an open 3D table (OpenGL ES 3.0, additive lines on black = transparent on the
waveguide, one draw per eye).

## Intro screen — basic options

Swipe up/down to pick a row, left/right (or tap) to change it:

- **START**
- **DIFFICULTY** — Easy / Normal / Hard (pursuer speed and how long the
  power pill frightens them: 7 / 5 / 3 seconds)
- **MAZE** — NEON (tighter corridors) / OPEN (airier remix layout)
- **LIVES** — 3 / 5

Options persist. High score persists.

## Controls

| Gesture | Action |
|---|---|
| **Swipe** (4-way) | Queue a turn — taken at the next opening, classic style |
| **Tap** | Select on menus |
| **Double-tap** | Pause (Resume / Restart / Quit) |

## The game

- **Pellets 10, power pills 50**, frightened pursuers 200-400-800-1600 in a
  chain, **fruit** at 70 and 170 pellets (100 × level), **extra life at
  10,000**.
- The four brains, faithful in spirit: the shadow chases your tile, the
  ambusher aims four ahead, the bashful one mirrors a lunge off the shadow,
  and the pokey one loses its nerve up close.
- **Scatter/chase waves** on the classic timer; power pills reverse everyone.
  Eaten pursuers race home as eyes and re-form.
- Each level speeds everyone up; the **siren rises in pitch as the maze
  empties** — the classic pressure, synthesized live.
- Mazes are hand-drawn and **self-healed at load**: a flood-fill from the
  spawn converts any unreachable pellet into empty space, so a level can
  never soft-lock.

## Sound

All synthesized at first launch, zero audio binaries: the alternating
two-note waka, the rising siren loop, the frightened bubble loop, power-pill
gulp, pursuer-swallowed arpeggio, the falling death wail, fruit and extra-life
jingles, ready and clear fanfares, UI ticks.

## Build & install

```bash
cd ~/Projects/TapMuncher
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17, AGP 8.7.3, Kotlin 2.0.21, compileSdk 35 / minSdk 29, zero
dependencies, zero vendor AARs. Binocular SBS auto-enables on RayNeo hardware
(detected by manufacturer identity, never `Build.MODEL` — it reports
`ARGF20`). Audio runs off the render thread.
