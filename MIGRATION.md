# Northstar Redux — Spaceship Physics Migration

Status: **design + licensing groundwork complete. Physics engine swap not yet implemented.**

This document records the verified findings, the target architecture, the
file-by-file migration plan, the mixin conflict analysis and the required
`build.gradle` changes for replacing Northstar's contraption-based space
movement with rigid-body spaceship physics.

---

## 1. Current state: what Northstar actually does today

Northstar's space movement is **not** constraint-based and **not** rigid-body. It
is a vertically-constrained Create contraption with hand-rolled integration.

| Concern | Location | Behaviour |
| --- | --- | --- |
| Contraption | `contraption/rocket/RocketContraption.java` | extends `TranslatingContraption`; no rotation, vertical only |
| Flight entity | `contraption/rocket/RocketContraptionEntity.java` (950 lines) | custom gravity integration, teleport-based dimension crossing |
| Thrust | same file, `ASCENDING` branch | `accel = -gravity + (thrusterCount * thrusterPower) / weight`, clamped to `MAX_ACCELERATION = 0.5f`, applied as `velocity += accel * 0.05f` |
| Speed cap | same file | `MAX_SPEED = 5f` blocks/tick; `MIN_DESCEND_SPEED = 0.1f` |
| Fuel | `RocketContraption.calculateRequiredFuel` / `consumeFuel` | **one-shot energy budget** charged at launch, not continuous burn |
| Collision | `RocketContraptionEntity.checkCollisionDistance` | manual AABB sweep, assumes grid alignment |
| Seats | `virtualSeats` map + `changeDimension` re-attach | contraption-native seat handling |
| Phases | `LaunchStatus` enum | `WAITING, COUNTDOWN, ASCENDING, DESCENDING` |

Consequences of the current design that the migration must resolve:

- **No rotation.** The rocket cannot pitch, yaw or roll. Thrusters all point up.
- **No continuous propellant burn.** Fuel is a scalar budget deducted once at
  countdown end, so throttle level has no effect on fuel use.
- **Dimension crossing is a teleport**, not physical flight (`changeDimension`
  at the build-height boundary, with a `TRANSPORT_DELAY_TICKS` pause).
- **Collision is bespoke** and will not interact correctly with physics bodies.

Northstar currently has **no** ship/vessel/sublevel/rigidbody abstraction of its
own. The only `SubLevel` references are in its Sable compatibility layer.

---

## 2. Verified target API: Sable 2.0.3

Cosmonautics does **not** use Valkyrien Skies. It uses **Sable**
(`dev.ryanhcode.sable`), a Rapier-backed rigid-body library, consumed from Maven.

Resolved coordinates (see `gradle/libs.versions.toml`):

```
sable            = dev.ryanhcode.sable:sable-neoforge-1.21.1:2.0.3
sable-companion  = dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:1.6.0
```

`sable-neoforge-2.0.3` is a 13 MB fat jar that **jar-in-jars** `sable_rapier
2.0.3`, `sable-companion 1.6.0` and `veil 4.1.4`.

> Caution when reading Sable source: `sable-common` resolves to **1.1.3** in the
> Gradle cache while `sable-neoforge` is **2.0.3**. The 1.1.3 sources jar has a
> *different* API shape (e.g. flattened constraint records). Verify signatures
> against the 2.0.3 jar with `javap -cp <sable-neoforge jar> <fqcn>` before
> writing code against them.

### 2.1 Signatures that matter (verified via `javap` on sable-neoforge 2.0.3)

```java
// Assembly: lift a set of world blocks into a free rigid-body sublevel
public final class SubLevelAssemblyHelper {
    public static ServerSubLevel assembleBlocks(
        ServerLevel level, BlockPos origin,
        Iterable<BlockPos> blocks, BoundingBox3ic bounds);
    public static GatherResult gatherConnectedBlocks(
        BlockPos start, ServerLevel level, int limit, FrontierPredicate pred);
    public static void kickFromContainingSubLevel(
        ServerLevel, SubLevelPhysicsSystem, PhysicsPipeline, ServerSubLevel, SubLevel);
}

// Per-physics-tick hook implemented by block entities inside a sublevel
public interface BlockEntitySubLevelActor {
    default void sable$tick(ServerSubLevel subLevel);
    default void sable$physicsTick(
        ServerSubLevel subLevel, RigidBodyHandle body, double dt);
    default Iterable<SubLevel> sable$getLoadingDependencies();
    default Iterable<SubLevel> sable$getConnectionDependencies();
}

// Force application
public final class ForceGroups {
    public static RegistryObject<ForceGroup> GRAVITY, DRAG, LEVITATION,
        BALLOON_LIFT, PROPULSION, LIFT, MAGNETIC_FORCE;
}
public class ForceTotal {
    public void applyForces(RigidBodyHandle body);
    public void applyForceTotal(ForceTotal other);
    public void applyLinearImpulse(Vector3dc v);
    public void applyAngularImpulse(Vector3dc w);
    public void applyTorqueImpulse(Vector3dc t);
    public void applyImpulseAtPoint(MassData mass, Vector3dc impulse, Vector3dc point);
    public Vector3d getLocalForce();
    public Vector3d getLocalTorque();
    public void reset();
}

public class RigidBodyHandle {
    public static RigidBodyHandle of(ServerSubLevel subLevel);
    public void applyImpulseAtPoint(Vector3dc impulse, Vector3dc point);
    public void applyImpulseAtPoint(Vec3 impulse, Vec3 point);
    public void applyLinearAndAngularImpulse(Vector3dc v, Vector3dc w);
    public void applyLinearImpulse(Vector3dc v);
    public void applyAngularImpulse(Vector3dc w);
    public void applyTorqueImpulse(Vector3dc t);
    public void applyForcesAndReset(ForceTotal total);
    public Vector3dc getLinearVelocity();
    public Vector3dc getAngularVelocity();
    public void addLinearAndAngularVelocity(Vector3dc v, Vector3dc w);
    public void teleport(Vector3dc pos, Quaterniondc rot);
    public boolean isValid();
}

// Mass properties
public interface MassTracker extends MassData {
    static MassTracker build(BlockGetter level, BoundingBox3ic bounds);
    static BiFunction<BlockGetter, BlockState, Vector3dc> BLOCK_CENTER_OF_MASS;
}
public interface MassData {
    double getMass(); double getInverseMass();
    Matrix3dc getInertiaTensor(); Matrix3dc getInverseInertiaTensor();
    Vector3dc getCenterOfMass();
}

// Seating / passenger transfer
public final class SubLevelHelper {
    public static void pushEntityLocal(SubLevel, Entity);
    public static void popEntityLocal(SubLevel, Entity);
    public static void pushEntityLocal(SubLevel, Entity, EntityAnchorArgument.Anchor);
    public static void popEntityLocal(SubLevel, Entity, EntityAnchorArgument.Anchor);
    public static Vector3d getVelocityRelativeToAir(Level, Vector3dc, Vector3d);
}

// Existing Northstar integration point
public final class SableCompanion {           // sable-companion, MIT
    public SubLevelAccess getContaining(Level level, BlockPos pos);
}
```

There is also `dev.ryanhcode.sable.api.sublevel.KinematicContraption`, an
interface a Create contraption implements to be lifted into a Sable sublevel
(`sable$getLocalBounds`, `sable$blockGetter`, `sable$getMassTracker`,
`sable$getPosition(double)`, `sable$getOrientation(double)`, `sable$liftProviders`,
`sable$shouldCollide`, `sable$isValid`).

---

## 3. Target architecture

```
RocketStationBlockEntity.assemble()
  └─ gather blocks (reuse RocketContraption.capture() scanning)
  └─ SubLevelAssemblyHelper.assembleBlocks(level, pos, blocks, bounds)
       └─ ServerSubLevel  (free 6-DOF rigid body, mass from MassTracker.build)
            └─ RocketThrusterBlockEntity implements BlockEntitySubLevelActor
                 └─ sable$physicsTick(subLevel, body, dt)
                      ├─ resolve thrust axis from block face + body orientation
                      ├─ accumulate into ForceTotal under ForceGroups.PROPULSION
                      ├─ consume propellant continuously (rate ∝ throttle)
                      └─ body.applyForcesAndReset(total)
```

Key semantic changes:

1. **Assembly** produces a Sable `ServerSubLevel`, not a `RocketContraption`.
2. **Flight** is Rapier integration, not `velocity += accel * 0.05f`.
3. **Thrust** is per-thruster force at a world point, producing both linear
   acceleration and torque from off-axis placement. No global `thrusterCount`.
4. **Propellant** burns per tick proportional to commanded thrust, replacing the
   one-shot `FuelCost` budget.
5. **Gravity** comes from Sable's `ForceGroups.GVITY`, replacing Northstar's
   `level.northstar$gravity()` applied by the contraption entity.
6. **Seating** uses `SubLevelHelper.pushEntityLocal` / `popEntityLocal`.
7. **Collision** is Rapier's, replacing `checkCollisionDistance`.

---

## 4. File-by-file migration plan

### Phase 1 — Foundations (no behaviour change; safe to land first)

| File | Change |
| --- | --- |
| `compat/sable/NorthstarSable.java` | Keep. This is the existing, working entry point; extend it with shared handle/pose caching rather than duplicating lookups. |
| `mixin/compat/sable/ServerBoundPunchSubLevelPacketMixin.java` | Keep, but pin to the Sable version it targets (see §6). |

### Phase 2 — Vessel assembly

| File | Change |
| --- | --- |
| `block/tech/rocket_station/RocketStationBlockEntity.java` | `assemble()` builds a `ServerSubLevel` via `SubLevelAssemblyHelper.assembleBlocks` instead of `RocketContraptionEntity.create`. Retain `destination`, `lastException`, and the 2-slot container unchanged. |
| `contraption/rocket/RocketContraption.java` | Reduce to a **block scanner** producing a plain assembly description (bounds, block list, `thrusterPositions`, `seatPositions`, `fuelTanks`, `hasControls`, `hasAutoLander`, `hasInterplanetaryNavigator`). Strip `calculateRequiredFuel`, `consumeFuel`, `calculateShapeVolume`, collider caching. Keep the tag-driven mass weighting — it feeds `MassTracker`. |
| `content/NorthstarEntityTypes.java` | Remove `ROCKET_CONTRAPTION`; no entity is needed since the sublevel *is* the vessel. Update the renderer binding. |

### Phase 3 — Propulsion

| File | Change |
| --- | --- |
| `block/tech/rocket_thruster/RocketThrusterBlock.java` | Add thrust direction from block face (currently `TOP`/`BOTTOM` only, vertical). Introduce a tier property. |
| `block/tech/rocket_thruster/RocketThrusterBlockEntity.java` | **New.** Implements `BlockEntitySubLevelActor`; `sable$physicsTick` accumulates `ForceTotal` and consumes propellant. |
| `block/tech/rocket_thruster/RocketThrusterMovementBehaviour.java` | Delete. `MovementBehaviour` is a contraption concept with no meaning inside a Sable sublevel. Move the plume particle spawn into the new block entity's client tick. |
| New `com/lightning/northstar/physics/RocketPropulsion.java` | Owns the force/fuel math so it is unit-testable without a running world. Take `RigidBodyHandle`, `MassData`, and the thruster list. |
| `config/ServerConfig.java` | `thrusterPower` becomes a force in newtons per thruster tier; add propellant burn rate and specific impulse. Retire `takeoffFuelScale` / `travelFuelScale` / `landingFuelScale`. |

### Phase 4 — Flight, seats, travel

| File | Change |
| --- | --- |
| `contraption/rocket/RocketContraptionEntity.java` | Remove. Its 950 lines of integration, `checkCollisionDistance`, `calculateLandingHeight` and `changeDimension` are replaced by Rapier + sublevel bookkeeping. |
| `contraption/rocket/LaunchStatus.java` | Keep for UI/HUD state, but it becomes a thin state machine over sublevel state, not a physics driver. |
| `contraption/rocket/RocketSyncPacket.java` | Re-point to carry sublevel pose + throttle for the HUD instead of a scalar `velocity`. |
| New `physics/RocketShipState.java` | Destination, phase, throttle, fuel remaining, autopilot. Survives dimension changes. |
| `block/tech/rocket_controls/` | Inputs become thrust-vector commands (pitch/yaw/roll/throttle) instead of a binary spacebar. |

### Phase 5 — Content

| File | Change |
| --- | --- |
| `ponder/scene/RocketStationPonder.java` | Re-word to physics terminology; the current text describes thrust counts and fuel budgets. Update `assets/northstar/ponder/rocket.nbt` accordingly. |
| `content/NorthstarCreativeModeTab.java`, lang files | Update tooltips for the new propellant/throttle model. |

---

## 5. Mixin conflict analysis (verified)

Comparison of `@Mixin` targets across both trees: **Northstar 100 distinct
targets, Create: Cosmonautics 27.**

### 5.1 Direct collisions — both mods inject into the same class

| Target class | Northstar mixins | Cosmonautics mixin |
| --- | --- | --- |
| `net.minecraft.world.entity.Entity` | `entity.EntityMixin`, `gravity.EntityGravityMixin` | `EntityMixin` |
| `net.minecraft.world.entity.LivingEntity` | `entity.LivingEntityMixin`, `gravity.LivingEntityGravityMixin`, `gravity.EntityFallDamageGravityMixin`, `oxygen.LivingEntityMixin` | `LivingEntityMixin` |
| `net.minecraft.client.multiplayer.ClientPacketListener` | — | `ClientPacketListenerMixin` |
| `net.minecraft.client.renderer.LevelRenderer` | `client.LevelRendererMixin` | `LevelRendererMixin` |
| `net.minecraft.client.Minecraft` | `client.MinecraftMixin` | `MinecraftMixin` |

These do **not** fail automatically — Mixin applies all of them — but every one
is a semantic conflict that must be reviewed by hand:

- `Entity`/`LivingEntity`: both mods implement gravity. Northstar has a
  `gravity` package applying per-planet gravity and fall damage; Cosmonautics
  applies Sable sublevel gravity. **Both will run.** Decide whether Northstar's
  gravity hooks must become a no-op for entities inside a Sable sublevel.
- `LevelRenderer`: both hook world rendering for their own visual layers.
- `Minecraft`: both likely touch frame/tick lifecycle.
- `ClientPacketListener`: Cosmonautics only — verify no Northstar packet
  ordering dependency once `RocketSyncPacket` changes shape.

### 5.2 Cosmonautics mixins into Sable implementation classes

These target Sable *internals* by string name with `remap = false`, so they do
not show up as "collisions" but are a real maintenance hazard — any Sable patch
release can break them silently at runtime:

```
PhysicsPipelineMixin                    -> dev.ryanhcode.sable.api.physics.PhysicsPipeline
RapierPhysicsPipelineMixin              -> (implementation pipeline)
RapierFixedConstraintHandleMixin        -> dev.ryanhcode.sable.physics.impl.rapier.constraint.fixed.RapierFixedConstraintHandle
RapierFreeConstraintHandleMixin         -> ...free.RapierFreeConstraintHandle
RapierGenericConstraintHandleMixin      -> ...generic.RapierGenericConstraintHandle
RapierRotaryConstraintHandleMixin       -> ...rotary.RapierRotaryConstraintHandle
SubLevelPhysicsSystemMixin              -> (implementation)
MergedMassTrackerMixin                  -> (mass tracker)
HoldingSubLevelMixin                    -> (holding sublevel)
SubLevelWarperMixin                     -> (sublevel warp)
```

Northstar has **no** mixins into Sable-owned classes today, so there is no direct
clash — but note Northstar's own
`compat.sable.ServerBoundPunchSubLevelPacketMixin` is coupled to Sable in exactly
the same way. Recommendation: gate every Sable-internal mixin behind an exact
version check (see §6) and prefer Sable's public API over internals wherever it
is sufficient.

### 5.3 Vendored API shadowing

`other mods/Create-Cosmonautics/src/main/java/dev/ryanhcode/sable/api/physics/constraint/`
contains 8 hand-written interface shims that shadow Sable's own classes of the
same name (e.g. a `FixedConstraintHandle` that extends the real
`dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle`). If this tree
is ever merged into a shared source set, these will shadow the real API and the
`Rapier*ConstraintHandleMixin` classes depend on the nested names. Decide
explicitly whether to delete them or keep them as intentional compat shims —
they must not both be present in one compilation unit.

---

## 6. Required build changes

### 6.1 Sable must become a required dependency

`src/main/resources/META-INF/neoforge.mods.toml` currently declares:

```toml
[[dependencies.northstar]]
modId = "sable"
type = "optional"
```

Once flight depends on Sable, change `type = "optional"` to
`type = "required"`. This is a **breaking packaging change**: Sable becomes
mandatory for every pack and launcher profile that loads Northstar. Note Sable
itself requires Create, Flywheel and Sodium, so the required set grows.

Also flip `sablecompanion` to required, or drop the dependency entirely — see
§6.2.

### 6.2 Remove the redundant `sable-companion` dependency

`sable-neoforge-2.0.3` already jar-in-jars `sable-companion-common-1.21.1-1.6.0`
(confirmed in its `META-INF/jarjar/metadata.json`). Northstar declares it
separately in `build.gradle.kts`:

```kotlin
implementation(libs.sable)
implementation(libs.sable.companion)   // redundant — bundled by libs.sable
implementation(libs.aeronautics)
```

`sable-companion 1.6.0` is **MIT** (© 2026 RyanHCode), whereas Sable itself is
**PolyForm Shield 1.0.0** — not an OSI-approved license. Depending on Sable means
Northstar cannot be redistributed as a fully self-contained open-source package;
users must obtain Sable separately and accept its terms.

`aeronautics` (`maven.modrinth:create-aeronautics:1.3.2+mc1.21.1`) is already
declared and should stay optional unless the new vessel code requires it.

### 6.3 Version pinning

The Sable-internal mixins listed in §5.2 resolve targets by string name. Add an
explicit version guard so a Sable update fails loudly at load rather than
silently at runtime. Northstar already has
`dev.ryanhcode.sable.annotation.MixinModVersionConstraint` available in the API
for this purpose.

---

## 7. Registry and tag harmonization (objective 2)

### 7.1 Already aligned — no action needed

Both mods use the `c:` namespace for common tags (Northstar via
`content/NorthstarTags.java:46`, `COMMON("c")`), and the titanium tag paths
already agree:

| Tag | Northstar | Cosmonautics |
| --- | --- | --- |
| `c:ingots/titanium` | `C_INGOTS_TITANIUM` | `MetalTags.TITANIUM.ingots` |
| `c:plates/titanium` | `C_SHEETS_TITANIUM` | `MetalTags.TITANIUM.plates` |
| `c:nuggets/titanium` | `C_NUGGETS_TITANIUM` | `MetalTags.TITANIUM.nuggets` |
| `c:raw_materials/titanium` | `C_RAW_MATERIALS_TITANIUM` | `MetalTags.TITANIUM.rawOres` |
| `c:ores/titanium` | `C_ORES_TITANIUM` | `MetalTags.TITANIUM.ores` |

(Note the Northstar enum constant is named `C_SHEETS_TITANIUM` but its path is
`plates/titanium` — that matches Create and Cosmonautics. The constant name is
the only oddity; the resolved tag is correct.)

### 7.2 Duplicate item registrations to eliminate

Cosmonautics registers its own titanium items in
`registry/RocketItems.java:66-72` alongside Northstar's
`content/NorthstarItems.java:45-75`:

| Cosmonautics item | Northstar equivalent | Action |
| --- | --- | --- |
| `rocketnautics:titanium_ingot` | `northstar:titanium_ingot` | delete Cosmonautics entry; repoint recipes |
| `rocketnautics:raw_titanium` | `northstar:raw_titanium_ore` | delete; repoint recipes |
| `rocketnautics:crushed_raw_titanium` | *none* | see §7.3 |
| `rocketnautics:titanium_alloy` | *none* | see §7.3 |

Northstar's registry is canonical, per the stated requirement.

### 7.3 Blocking gap — requires assets

Cosmonautics references two materials Northstar does not have:
`titanium_alloy` (`c:ingots/titanium_alloy`) and crushed titanium
(`c:crushed_raw_materials`). Removing those registrations without replacements
will break every Cosmonautics recipe that consumes them.

Resolving this needs new textures, block/item models and lang entries. Those are
binary/visual assets and are deliberately **not** machine-generated here (see
`AI_POLICY.md` §4 in the Cosmonautics repository). A human must author:

- `northstar:titanium_alloy_ingot` + tag `c:ingots/titanium_alloy`
- `northstar:crushed_raw_titanium` + tag `c:crushed_raw_materials`
- their textures, models and `en_us.json` entries

Until those exist, the recipe redirection in §7.2 is incomplete.

---

## 8. What has actually been done

- [x] Baseline build verified (`./gradlew compileJava --offline`, exit 0)
- [x] Relicensed MIT → GPL-3.0 (`LICENSE.md` now carries the verbatim FSF
      GPL-3.0 text; `neoforge.mods.toml` `license = "GNU GPL v3"`), with the
      pre-existing MIT copyright notices retained
- [x] `CREDIT.txt` written with full attribution and license inventory
- [x] Sable 2.0.3 public API mapped and verified with `javap`
- [x] Mixin conflict analysis completed against both real source trees
- [x] Tag namespace harmonization verified as already-aligned
- [x] `build.gradle.kts` dependency findings documented (§6)

## 9. What remains

- [ ] Phase 2 — vessel assembly into a Sable sublevel
- [ ] Phase 3 — thruster block entity + `RocketPropulsion` force/fuel math
- [ ] Phase 4 — flight state, seats via `SubLevelHelper`, dimension travel,
      HUD packet, control input rework
- [ ] Phase 5 — Ponder, tooltips, guidebook
- [ ] §6.1 flip Sable to a required dependency
- [ ] §6.2 drop the redundant `sable-companion` declaration
- [ ] §7.3 author the two missing titanium materials
- [ ] Resolve the 5 semantic mixin collisions in §5.1
- [ ] Decide the vendored-shim question in §5.3

## 10. Outstanding legal prerequisite

Relicensing to GPL-3.0 was performed on the fork's own authority. The upstream
project `Astronauts-of-Create/Northstar-Redux` holds copyright in the inherited
portions; their permission must be obtained before publishing. See `CREDIT.txt`
§1.
