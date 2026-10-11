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
(Tools & Utilities creative tab). Whoever inserts the Command Order owns the unit.

The Nav Table outputs redstone on the side facing its target. Wire it like this:

- **Steering:** Nav Table left side → Wheel Mounts' left side, right → right (Create Redstone Links work).
  If the vehicle steers away from the target, swap them.
- **Throttle:** Nav Table **back** side → an **Analog Transmission** between your engine and the wheels
  (or → the block above each Wheel Mount, which brakes). The signal means *stop*:
  - 0 while the target is ahead (full speed)
  - rising as the target swings behind the vehicle
  - 15 on every side once the unit has arrived (within 4 blocks) or has no order
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

The first shot at a target is a ranging shot. The mod measures the real shell (speed, gravity, drag) and aims
later shots with it, so loading a different charge or shell takes one shot to adjust to.

## Development

```sh
./gradlew runClient   # dev client with all dependencies (pulled from Modrinth's Maven)
./gradlew build       # jar in build/libs, runs the ballistics unit tests
```

Code map (`src/main/java/com/strategywar`):

- `CommandTarget`: the Nav Table target (steering and halt signal), unit ownership, order handling
- `Gunnery`: turret aiming, firing, measuring the ranging shot, parking on cease fire
- `Ballistics`: shell flight model and elevation solver (pure Java, unit tested)
- `Order` / `UnitList`: client→server orders, server→client unit positions
- `client/StrategyScreen`: strategy camera, selection, markers, cursor picking

## Known limits

- Selection picks by the Nav Table's position; click near it on long vehicles.
- A target exactly behind a unit gives no steering, so it won't turn around; click off to the side.
- Aiming treats the muzzle as 2 blocks above the mount and handles low arcs only (up to 40°).
- Command Orders inserted by hoppers or deployers have no owner and obey anyone.
