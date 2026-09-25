# ChunkPilot

**Direction-aware chunk loading for high-speed travel** — chunks **ahead of you** are loaded, generated and delivered earlier, while the request rate adapts to server load.

Built for elytra flight, trains and airships; the benefit is largest on servers, networks or clients that are already struggling.

| | |
|---|---|
| **Platforms** | Fabric + NeoForge + Forge |
| **Minecraft** | 1.20.1 – 1.21.11, 26.1 – 26.3 (see the version table below) |
| **Java** | 21 (1.21.x) / 25 (26.x) |
| **License** | MIT |

---

## The problem it solves

Vanilla chunk loading has three layers, and **every one of them sorts by distance only — never by direction**:

| Stage | Vanilla behaviour |
|---|---|
| **Generation** | The PLAYER ticket produces a **symmetric box** of levels centred on the player |
| **Sending** | `ChunkTrackingView` is likewise a **symmetric window** centred on the player |
| **Queueing** | Sorted by Chebyshev distance only — straight ahead and straight behind have identical priority |

Flying north, the chunk 5 blocks north and the chunk 5 blocks south have exactly the same priority — **half the worldgen budget is spent on the terrain behind you**.

ChunkPilot shifts the whole "load window + client-visibility window" **forward**:

```
Vanilla:    [ 10 chunks | player | 10 chunks ]      symmetric
ChunkPilot: [  4 chunks | player | 16 chunks ]      +6 ahead, unchanged behind
```

---

## Features

### 1. Forward look-ahead window

Places anchor tickets **ahead of** the player and shifts the client visibility window the same way:

```toml
[forward_window]
enabled = true
aheadChunks  = 13    # anchor displacement
forwardExtra = 6     # generation-side forward extension: reach = view-distance + 6
trackingShift = 6    # sending-side shift
```

**Design note**: both the anchor level field and the player level field are 1-Lipschitz, and the minimum of two 1-Lipschitz fields is still 1-Lipschitz ⇒ no "large level step between neighbours" that would break the load chain. The further the anchor, the cheaper a given forward extension is (the extra loaded area is smallest when `A = view-distance + forwardExtra/2`).

### 2. Non-blocking collision queries (on by default)

The direct root cause of "invisible walls" and rubber-banding. At high speed the main thread waits synchronously on chunks that are not finished generating:

```
handleMovePlayer → collectColliders → Level.getChunkForCollisions
  → ServerChunkCache.getChunk → managedBlock → parkNanos   ← main thread parks here
```

A single park has been measured at **2.8 – 45 seconds** → `Can't keep up` → watchdog force-kill. With this enabled, a not-yet-ready chunk simply "does not exist" for collision (matching vanilla **client** behaviour) and the main thread never parks.

### 3. Adaptive load shedding

Adjusts request rate from server MSPT, with an **in-flight request window** (`maxOutstandingRequests`) and a TTL fallback — the request rate follows actual worldgen throughput instead of crushing the server.

### 4. Diagnostic commands

```
/chunkpilot diagnose      # demand vs throughput, forward frontier, C2ME advice, view-distance/speed advice
/chunkpilot probe <x> <z> # read-only probe: is it loaded / what ticket level (never loads a chunk)
```

---

## Measurements

> Unless stated otherwise: sustained top elytra speed **34 m/s** (1.70 blocks/tick), view-distance 10, fresh terrain, 60 s cruise.

### Single-player long-distance (150 s continuous flight, 5100 blocks)

Fabric 1.21.3 with C2ME.

| Metric | Baseline (window off) | **ChunkPilot** |
|---|---|---|
| Footprint coverage | 1.0 | **1.0 (600/600 ticks)** |
| Delivered before entering the chunk | 100% | **100%** |
| Unloaded while standing inside a chunk | 0 | **0** |
| Server corrections | 0 | 1 per 150 s, displacement only 1.72 blocks (≈0.05 s) |
| Cruise MSPT | 8.8 – 10.9 ms | 8.79 ms mean / 14.0 ms peak, **0** samples >50 ms |
| Forward frontier (chunks) | 12.26 | **15.07** |
| Forward-loaded chunks | 111 | **140** |
| Forward-loaded share | 0.326 | **0.464** |

> **Baseline (window off)** = the identical configuration — same server, same route, same C2ME setup — with the forward window **disabled**: ChunkPilot without the feature this section is about. Those figures come from a 60 s cruise of the same profile.

**Vanilla baseline** — single player, 34 m/s, no C2ME, mod removed (`--cp off` with the jar moved away), two 60 s cruises:

| Metric | Vanilla | ChunkPilot |
|---|---|---|
| Coverage | 0.43 – 0.63 | 0.38 – 0.42 |
| Received chunks | 794 – 1489 | 1245 – 1263 |
| Forward frontier (chunks) | 0.80 – 1.83 | **2.40 – 4.13** |
| Forward-loaded share | 0.028 – 0.053 | **0.217 – 0.453** |
| Forward / behind ratio | 0.03 – 0.07 | **0.41 – 1.36** |

> No mod-removed run was recorded over 150 s, so the vanilla baseline above is the same single-player profile over 60 s. Without C2ME the server's throughput is the binding constraint, so absolute coverage stays low in both groups — what changes is *where* the delivered chunks go.

⇒ **"more chunks loaded ahead" ✅ (frontier +23%, forward-loaded +26%); "walls / rubber-banding" ✅ (100% delivered in advance, 0 unloads while standing inside a chunk, and over 2.5 minutes only a single 1.7-block instantaneous correction).**

### Without C2ME

Vanilla throughput is the hard constraint (≈20–40 chunks/s for the whole server). ChunkPilot **cannot create throughput**, but it can concentrate the limited throughput ahead of the player. Measured with 5 players at 34 m/s:

| Group | Coverage | Corrections | Forward frontier | Forward-loaded share |
|---|---|---|---|---|
| Vanilla | 0.170 | 115 | 0.97 | 0.060 |
| **ChunkPilot** | **0.242** | **27** | **2.85** | **0.195** |

⇒ Coverage **1.4×**, corrections **1/4**, forward concentration **3.3×** — no longer a net regression.

---

## Configuration

Config file: `config/chunkpilot.toml` (generated on first start)

```toml
[general]
enabled  = true
logLevel = "info"
language = "auto"        # auto | en_us | zh_cn — player messages follow each client's language

[forward_window]
enabled       = true
aheadChunks   = 13       # anchor displacement
forwardExtra  = 6        # generation-side forward extension
trackingShift = 6        # shift of the sending-side visibility window

[protection]
maxMspt              = 40   # above this, shed load
disableMspt          = 50   # above this, back off completely
nonBlockingCollision = true # fixes "walls" / rubber-banding (on by default, strongly recommended)
nonBlockingReads     = false
nonBlockingGetChunk  = false # emergency switch: never parks, but delivery drops 3–4×
```

**Trade-off**: `aheadChunks=13, forwardExtra=6, trackingShift=6` is the empirically optimal combination — forward concentration ≈0.58, frontier ≈14 chunks, and MSPT in the same band as vanilla. Larger shifts (e.g. `10/8/8`) reach 0.70+ concentration but push the MSPT peak to 57.7 ms, which is not worth the cost.

---

## Commands

All commands require OP. Language follows the executing client (console/RCON use English).

| Command | Purpose |
|---|---|
| `/chunkpilot status` | Current status |
| `/chunkpilot diagnose` | Health check: demand vs throughput, forward frontier, C2ME advice |
| `/chunkpilot player [name]` | Per-player detail (speed / heading / ticket count) |
| `/chunkpilot net [name]` | Per-player network statistics |
| `/chunkpilot probe <x> <z>` | **Read-only** probe (never loads a chunk) |
| `/chunkpilot gen [queue\|stats]` | Generator state |
| `/chunkpilot send [status]` | Send-scheduler state |
| `/chunkpilot reload [config]` | Hot-reload config / reload view distance |
| `/chunkpilot config show` | Print the effective config |
| `/chunkpilot lang [auto\|en_us\|zh_cn]` | Switch output language |
| `/chunkpilot help` | Command list |

---

## Supported versions

`main` targets Fabric 1.21.3 + NeoForge 1.21.1. Other versions live on their own branches:

| MC | NeoForge | Fabric API | Branch |
|---|---|---|---|
| 1.20.1 | `1.20.1-47.1.106` (artifact name is `forge`) | `0.92.12+1.20.1` | `port/1.20.1` · `forge/1.20.1` |
| 1.21.1 | `21.1.251` | `0.116.17+1.21.1` | `port/1.21.1` |
| 1.21.2 | `21.2.1-beta` | `0.106.1+1.21.2` | `port/1.21.2` |
| 1.21.3 | `21.3.97` | `0.114.1+1.21.3` | `port/1.21.3` |
| 1.21.4 | `21.4.157` | `0.119.4+1.21.4` | `port/1.21.4` |
| 1.21.5 | `21.5.98` | `0.128.2+1.21.5` | `port/1.21.5` |
| 1.21.6 | `21.6.20-beta` | `0.128.2+1.21.6` | `port/1.21.6` |
| 1.21.7 | `21.7.25-beta` | `0.129.0+1.21.7` | `port/1.21.7` |
| 1.21.8 | `21.8.54` | `0.136.1+1.21.8` | `port/1.21.8` |
| 1.21.9 | `21.9.16-beta` | `0.134.1+1.21.9` | `port/1.21.9` |
| 1.21.10 | `21.10.64` | `0.138.4+1.21.10` | `port/1.21.10` |
| 1.21.11 | `21.11.45` | `0.141.6+1.21.11` | `port/1.21.11` |
| 26.1 / 26.2 / 26.3 | see `build.gradle` on the `port/26.x` branches | same | `port/26.1` · `port/26.2` · `port/26.3` |

> Versions marked *beta* only have beta/rc builds published by NeoForge and may behave differently from stable releases.

**JDK must match the version**: use **JDK 21** for 1.21.x and **JDK 25** for 26.x. Using the wrong one fails at startup with something like `ClassNotFoundException: java.lang.Double` — an error that **looks like a mod bug but is actually the wrong JDK**.

---

## Building from source

**Requirements**: JDK 21, Gradle 9.6.1

```bash
export JAVA_HOME=/path/to/jdk-21
export GRADLE_USER_HOME=/path/to/gradle-home

gradle :common:test :fabric:build :neoforge:build \
  -x remapSourcesJar --no-daemon --no-parallel
```

**Artifacts**:

| Platform | Path |
|---|---|
| Fabric | `fabric/build/libs/fabric-<version>.jar` |
| NeoForge | `neoforge/build/devlibs/neoforge-<version>-dev.jar` ⚠️ in `devlibs/`, not `libs/` |

> ⚠️ **A NeoForge build needs `.local-maven-repo/`**: `neoforge/build.gradle` relies on it to provide `cpw.mods:modlauncher:11.0.4` and `securejarhandler:3.0.8` (fancymodloader asks for 11.0.3, which is not on public Maven). That directory is not under version control, so a fresh clone must supply these two artifacts itself, otherwise `:neoforge:build` fails during dependency resolution.

**Icon**: the mod icon is a pair of identical PNGs — `common/src/main/resources/assets/chunkpilot/icon.png` (referenced by Fabric's `fabric.mod.json`) and `neoforge/src/main/resources/icon.png` (NeoForge's `logoFile`, path relative to the jar root). Replace both together. Note that `.gitignore` has a global `*.png` rule, and the icon is an **explicitly whitelisted** release asset — when editing `.gitignore`, do not break those two `!` exceptions, or clones will come without the icon and the references inside the jar will dangle.

---

## Known limitations

- **Without C2ME, throughput is a hard wall.** Demand = `Σ_players (speed/16) × (2×view-distance+1)`; vanilla sustains roughly 20–40 chunks/s. A single player at 34 m/s already exceeds the budget at view-distance 10, and 5 players at that speed cannot be served at any view distance. The only fixes are installing C2ME (×5 throughput) or reducing speed/view distance.
- `nonBlockingGetChunk` (off by default) makes the main thread never park, but starves the vanilla chunk pipeline and drops delivery 3–4× — kept as a "never crash the server" emergency switch; the mechanism is not fully understood yet.
- The `chunk_send` module is disabled by default (since 1.21.3 the sending path is handled by shifting the visibility window, so a separate reorder is no longer needed).

---

## License

MIT
