# Strategy War

A NeoForge mod that turns Minecraft into a Company of Heroes–style RTS, using vehicles you build yourself
with [Create Aeronautics](https://modrinth.com/mod/create-aeronautics) (Offroad wheels, Sable physics) and
[Create Big Cannons](https://modrinth.com/mod/create-big-cannons).

Press **G** to switch to a top-down strategy view, select your tanks, and order them to move and fire.

## Requirements

| Mod | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.259+ |
| Create | 6.0.10+ |
| Sable | 2.0.x |
| Create Aeronautics (bundles Simulated + Offroad) | 1.3.2+ |
| Create Big Cannons | 5.11.7+ |
| Ritchie's Projectile Library (required by CBC) | 2.1.2+ |

## Building a unit

A unit is any assembled Sable vehicle with a **Navigation Table** (from Create Simulated) holding a **Command Order**
(Tools & Utilities creative tab). Insert the order **while facing the vehicle's front**: that sets which way is
forward, and whoever inserts it owns the unit.

The mod drives the vehicle through redstone on the Nav Table's four sides, relative to that front:

- **Left / right sides → steering:** to the Wheel Mounts' left / right sides (Create Redstone Links work).
  If the vehicle steers away from the target, swap them.
- **Back side → throttle:** to an **Analog Transmission** between engine and wheels (or the block above each
  Wheel Mount, which brakes). 15 means stop, 0 means full speed. It slows near the target and in sharp turns.
  The transmission only changes speed between its shaft and its **cogwheel**: take the drive out through the
  cog. A shaft run straight through it isn't throttled.
- **Front side → reverse:** to a Create **Gearshift** in the drive line. 15 while reversing.

Targets close behind a unit (within 20 blocks) are reversed into tail-first. Targets far behind, too close
to turn into, or a unit that stops making progress (circling or stuck) get a multi-point turn: back up with
opposite lock so the nose swings toward the target, then drive forward.
Turn radius and timings are constants at the top of `Driver.java`.

- **Cannons:** any Create Big Cannons **Cannon Mount** within 24 blocks of the Nav Table belongs to that unit.
  Assemble the cannon and keep it loaded; the mod aims and pulls the trigger.

## Strategy mode controls

| Input | Action |
|---|---|
| G | Enter / leave strategy mode (Esc also leaves) |
| WASD, Q/E, scroll | Pan, rotate, zoom the camera |
| Left-click / drag | Select a unit / box-select |
| Shift + select, Ctrl+A | Add to selection, select all |
| Right-click | Move selected units there |
| Ctrl + right-click | Attack the ground there |
| X | Cease fire (turrets return to neutral) |

Selected units show a readout under their marker: driving mode and the redstone level on each Nav Table side,
e.g. `TURN  L0 R15  throttle 8  reverse 15`. A red line means the unit isn't set up (e.g. no front set).

The first shot at a target is a ranging shot. The mod measures the real shell (speed, gravity, drag) and aims
later shots with it, so loading a different charge or shell takes one shot to adjust to.

## Development

```sh
./gradlew runClient   # dev client with all dependencies (pulled from Modrinth's Maven)
./gradlew build       # jar in build/libs, runs the ballistics unit tests
```

Code map (`src/main/java/com/strategywar`):

- `CommandTarget`: the Nav Table target, maps driver outputs to sides, unit ownership, order handling
- `Driver`: forward / reverse / arrived driving logic (pure Java, unit tested)
- `Gunnery`: turret aiming, firing, measuring the ranging shot, parking on cease fire
- `Ballistics`: shell flight model and elevation solver (pure Java, unit tested)
- `Order` / `UnitList`: client→server orders, server→client unit positions
- `client/StrategyScreen`: strategy camera, selection, markers, cursor picking

## Known limits

- Selection picks by the Nav Table's position; click near it on long vehicles.
- Aiming treats the muzzle as 2 blocks above the mount and handles low arcs only (up to 40°).
- Command Orders inserted by hoppers or deployers have no owner and obey anyone.
