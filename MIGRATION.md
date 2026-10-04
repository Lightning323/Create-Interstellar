# Northstar Redux Sable Migration

Status: implementation complete; game/runtime validation remains outstanding.

This records the migration from the legacy vertically constrained Create rocket
contraption to Sable 2.0.3 sublevels and Rapier rigid-body physics. Cosmonautics
material registrations and recipes were also redirected to Northstar's canonical
titanium items.

## Implementation Summary

- Sable is a required Northstar dependency. Northstar uses the Sable artifact's
  bundled companion API without a duplicate Gradle implementation dependency.
  The companion mod metadata remains required because Northstar calls that API.
- `RocketContraption` is now an assembly scanner. It records connected blocks,
  bounds, thrusters, seats, Create fuel tanks, control equipment, and mass/heat
  metadata. `RocketStationBlockEntity` lifts the blocks with
  `SubLevelAssemblyHelper.assembleBlocks` and initializes flight state on the
  resulting sublevel.
- `RocketContraptionEntity`, its renderer/registration, and legacy movement,
  actor, packet, and fuel-budget classes are removed. Rapier/Sable owns vessel
  movement, rotation, and collision.
- Thrusters implement `BlockEntitySubLevelActor`. Tiered thrust applies at each
  nozzle point under `ForceGroups.PROPULSION`; propellant burns continuously,
  with fractional-mB carry and drains from supported Create tank controllers
  or a thruster's own tank. Thruster tanks expose NeoForge fluid capabilities.
- `RocketPropulsion` contains pure impulse, moment, thrust-to-weight, and
  propellant calculations. Server config now defines three thrust tiers and
  specific impulse instead of one-shot ascent/travel/landing budgets.
- Keyboard pilot input commands throttle and pitch/yaw/roll. Gyrodynes use the
  commanded rates for attitude control. Controls and station interaction use
  `SubLevelHelper.pushEntityLocal` / `popEntityLocal`; the sync packet carries
  pose, throttle, phase, and fuel fraction for the flight HUD.
- Flight state is persisted in Sable sublevel user data. At configured build
  boundaries, a vessel is copied into a destination sublevel while preserving
  block data, orientation, linear/angular velocity, state, and passenger local
  positions.
- Create tank controller and last-known positions are rebased with the copied
  plot so multiblock tanks remain connected after a dimension handoff.
- The two missing Northstar materials (`titanium_alloy_ingot` and
  `crushed_raw_titanium`) are registered with common/Create tags and item
  textures. Cosmonautics no longer registers duplicate titanium ingot, raw ore,
  crushed ore, alloy ingot, elemental nugget, or elemental sheet items. The
  Cosmonautics-specific alloy nugget and sheet remain registered. Its recipes,
  loot, and tags point at Northstar items; registry aliases map retired item IDs
  for saves.

## File Modification Log

All paths below are relative to the Northstar repository root unless prefixed
with `other mods/Create-Cosmonautics/`. Northstar Java paths listed by basename
or package fragment use prefix `src/main/java/com/lightning/northstar/`; resource
fragments use `src/main/resources/`.

### Northstar

- Assembly and registry edits: `src/main/java/com/lightning/northstar/Northstar.java`,
  `NorthstarClient.java`, `NorthstarContentRemapper.java`,
  `block/tech/rocket_controls/RocketControlsBlock.java`,
  `block/tech/rocket_station/RocketStationBlock.java`,
  `RocketStationBlockEntity.java`, `RocketStationEditPacket.java`,
  `RocketStationHolder.java`, `RocketStationMenu.java`, `RocketStationScreen.java`,
  `block/tech/rocket_thruster/RocketThrusterBlock.java`,
  `RocketThrusterBlockEntity.java`, `compat/sable/NorthstarSable.java`,
  `content/NorthstarBlocks.java`, `NorthstarEntityTypes.java`, `NorthstarItems.java`,
  `NorthstarPackets.java`, `NorthstarTags.java`,
  `contraption/rocket/RocketContraption.java`,
  `contraption/rocket/packet/RocketSyncPacket.java`,
  `events/NorthstarCommonEvents.java`.
- Flight controls and user interface added:
  `src/main/java/com/lightning/northstar/block/tech/rocket_controls/RocketControlPacket.java`,
  `RocketFlightControls.java`,
  `src/main/java/com/lightning/northstar/client/renderer/RocketFlightOverlay.java`.
- Physics files changed: `src/main/java/com/lightning/northstar/config/ServerConfig.java`,
  `block/tech/gyrodyne/GyrodyneBlockEntity.java`, `physics/GyrodyneControl.java`,
  `physics/RocketPropulsion.java`, `physics/RocketShipState.java`.
- Physics and lifecycle files added:
  `src/main/java/com/lightning/northstar/physics/RocketFlightEvents.java`,
  `RocketFuelStore.java`, `RocketSublevelState.java`, `RocketSublevelWarper.java`.
- Gravity/Sable ownership guards changed:
  `src/main/java/com/lightning/northstar/mixin/gravity/AquaticEntityGravityMixin.java`,
  `EntityFallDamageGravityMixin.java`, `EntityGravityMixin.java`,
  `ExperienceOrbGravityMixin.java`, `FishingHookGravityMixin.java`,
  `LivingEntityGravityMixin.java`, `MobGravityMixin.java`, `PlayerGravityMixin.java`,
  `PotatoProjectileEntityGravityMixin.java`, `ProjectileGravityMixin.java`,
  `ServerGamePacketListenerImplGravityMixin.java`, `ThrowableItemGravityMixin.java`,
  `ThrownTridentGravityMixin.java`.
- Legacy-only files deleted:
  `src/main/java/com/lightning/northstar/block/tech/rocket_controls/RocketControlsInteractionBehaviour.java`,
  `RocketControlsMovementBehaviour.java`,
  `block/tech/rocket_station/RocketStationActor.java`,
  `RocketStationBlockMovementBehaviour.java`, `RocketStationBlockMovingInteraction.java`,
  `block/tech/rocket_thruster/RocketThrusterMovementBehaviour.java`,
  `content/NorthstarContraptionTypes.java`, `contraption/rocket/FuelCost.java`,
  `RocketAirSound.java`, `RocketContraptionEntity.java`, `RocketMovementBehaviour.java`,
  `contraption/rocket/packet/RocketDestinationPacket.java`, `RocketSeatsPacket.java`,
  `mixin/client/EntityRendererMixin.java`.
- Assets/data changed or added: `src/main/resources/northstar.mixins.json`,
  `assets/northstar/lang/default/base.json`,
  `assets/northstar/textures/item/crushed_raw_titanium.png`,
  `titanium_alloy_ingot.png`, and
  `data/northstar/physics_block_properties/heavy_blocks.json`,
  `super_heavy_blocks.json`.
- `build.gradle.kts` and Northstar's `neoforge.mods.toml` needed no edit: the
  Sable dependency was already required and `sable-companion` was already
  absent as a direct Gradle implementation dependency.

### Cosmonautics

- Source and metadata changed:
  `other mods/Create-Cosmonautics/src/main/java/dev/devce/rocketnautics/RocketNautics.java`,
  `registry/RocketBlocks.java`, `registry/RocketItems.java`,
  `registry/NorthstarMaterials.java` (added),
  `data/recipe/RocketCrushingRecipeGen.java`, `RocketMixingRecipeGen.java`,
  `RocketPressingRecipeGen.java`, `RocketStandardRecipeGen.java`,
  `RocketWashingRecipeGen.java`, and
  `src/main/resources/META-INF/neoforge.mods.toml`.
- Sable mixin guards added to
  `src/main/java/dev/devce/rocketnautics/mixin/EntityMixin.java`,
  `FluidTankBlockEntityMixin.java`, `HoldingSubLevelMixin.java`,
  `MergedMassTrackerMixin.java`, `PhysicsPipelineMixin.java`,
  `RapierFixedConstraintHandleMixin.java`, `RapierFreeConstraintHandleMixin.java`,
  `RapierGenericConstraintHandleMixin.java`, `RapierPhysicsPipelineMixin.java`,
  `RapierRotaryConstraintHandleMixin.java`, `SubLevelHoldingChunkAccessor.java`,
  `SubLevelHoldingChunkMapAccessor.java`, `SubLevelPhysicsSystemMixin.java`,
  `SubLevelWarperMixin.java`, `src/main/java/dev/egg/mixin/LevelPlotAccessor.java`,
  and `ServerLevelPlotAccessor.java`.
- Generated common/Create item tags changed:
  `src/generated/resources/data/c/tags/item/ingots.json`,
  `ingots/titanium.json`, `ingots/titanium_alloy.json`, `nuggets.json`,
  `nuggets/titanium.json`, `plates.json`, `plates/titanium.json`,
  `raw_materials.json`, `raw_materials/titanium.json`, and
  `data/create/tags/item/crushed_raw_materials.json`.
- Generated advancements changed under
  `src/generated/resources/data/rocketnautics/advancement/recipes/misc/`:
  `blasting/titanium_ingot_from_crushed.json`,
  `crafting/materials/raw_titanium_block_from_compacting.json`,
  `titanium_alloy_block_from_compacting.json`,
  `titanium_alloy_nugget_from_decompacting.json`,
  `titanium_block_from_compacting.json`, `titanium_ingot_from_compacting.json`,
  `titanium_nugget_from_decompacting.json`, and
  `smelting/titanium_ingot_from_crushed.json`.
- Generated ore loot tables changed:
  `src/generated/resources/data/rocketnautics/loot_table/blocks/titanium_ore.json`
  and `deepslate_titanium_ore.json`.
- Generated recipes changed under
  `src/generated/resources/data/rocketnautics/recipe/`: `blasting/` contains
  `titanium_ingot_from_crushed.json`, `titanium_ingot_from_ore.json`, and
  `titanium_ingot_from_raw_ore.json`; `crafting/materials/` contains
  `raw_titanium_from_decompacting.json`, `titanium_alloy_from_compacting.json`,
  `titanium_alloy_from_decompacting.json`, `titanium_ingot_from_compacting.json`,
  `titanium_ingot_from_decompacting.json`, and `titanium_nugget_from_decompacting.json`;
  `crushing/` contains `deepslate_titanium_ore.json`, `raw_titanium.json`,
  `raw_titanium_block.json`, and `titanium_ore.json`; also changed are
  `mixing/titanium_alloy.json`, `pressing/titanium_ingot.json`,
  `smelting/titanium_ingot_from_crushed.json`,
  `smelting/titanium_ingot_from_ore.json`,
  `smelting/titanium_ingot_from_raw_ore.json`, and
  `splashing/crushed_raw_titanium.json`.
- The adjacent checkout already had a deleted `AI_POLICY.md` in its Git status
  before migration work; it was not changed or restored here.

## Mixin Resolution

- Northstar's Sable packet mixin pins to exactly `2.0.3`. Cosmonautics' mixins
  targeting Sable physics, Rapier, sublevel, holding-chunk, and plot classes,
  plus mixins whose injected code calls Sable implementation APIs, share that
  exact version guard.
- Northstar gravity, fall-damage, movement, and dimension-boundary hooks yield
  while an entity is inside a Sable sublevel. Entity-specific overrides (living
  entities, mobs, aquatic entities, projectiles, hooks, and tridents) follow
  the same rule. This leaves sublevel motion to Sable instead of multiplying
  planetary gravity a second time.
- On `Entity#getGravity`, Cosmonautics supplies its dimension adjustment and
  Northstar leaves that value untouched inside a Sable sublevel; outside a
  sublevel, Northstar applies its planetary scale. The `LivingEntity#travel`
  changes similarly defer to Sable in a sublevel, while Cosmonautics cancels
  travel only when its own 6-DOF entity controller takes ownership.
- The `LevelRenderer` mixins independently alter Northstar planetary visuals
  and Cosmonautics star/cloud visuals; neither cancels the other's injections.
  Both modify sky-color and sunrise results, so the blend is order-sensitive
  and remains an in-game visual integration check. The two `Minecraft` mixins
  act on separate hooks (`runTick` versus screen/tick/music methods). Only
  Cosmonautics intercepts `ClientPacketListener.handleRespawn`; Northstar no
  longer has a client packet listener mixin or rocket-entity packet handler.
  Rocket state uses Catnip packet registration and Sable's pose synchronization.

## Verification And Limits

- Northstar `./gradlew compileJava --offline`: **BUILD SUCCESSFUL** after the
  migration changes.
- Modified Cosmonautics generated JSON: parsed successfully with `jq`.
- Cosmonautics compile was attempted online, but its `createMinecraftArtifacts`
  task failed before project source compilation: NeoForm's JDK compiler
  rejected `--release 21` (`release version 21 not supported`). This is an
  environment/toolchain failure, so Cosmonautics source compilation is not
  verified here.
- No game launch or automated test suite was run. In-game physics, multiplayer
  sync, rendering, tank connectivity, and boundary warping need manual testing.
- Dimension changes are explicit boundary handoffs that rebuild the Sable plot;
  they are not a single rigid body simulated continuously across two Minecraft
  dimensions. Arbitrary third-party block entities with extra coordinate
  references may need custom NBT rebasing.
- Gyrodyne hardware is required for commanded pitch/yaw/roll torque; thrusters
  alone provide force and off-centre torque but no attitude-hold controller.

## Manual Testing Checklist

1. Launch a dedicated server and client with Northstar, Sable 2.0.3,
   Create/Sable Companion, and the migrated Cosmonautics build. Confirm startup
   reports no missing required mods, mixin version mismatch, duplicate item
   registry, or recipe/tag parse error.
2. Place a rocket station, controls, at least one seat, a gyrodyne, several
   tier-1/2/3 thrusters facing different directions, and Create fluid tanks.
   Assemble. Confirm only the scanned hull is lifted into one Sable sublevel,
   the station remains behind, and the HUD identifies the vessel.
3. Before assembly, fill the Create tanks with a registered rocket fuel using
   fluid pipes. Also test filling a thruster's exposed fluid capability. Verify
   the station scanner reports the expected blocks, thrusters, seats, and tanks.
4. Board through the controls and sit on a Create seat. Confirm both passengers
   follow vessel translation and rotation. Press `B` to disembark; reboard and
   verify dimension handoff preserves passenger placement.
5. At zero throttle, confirm no thrust plume or fuel use. Raise throttle with
   `R`; check thrust points opposite each thruster face, the vessel accelerates
   and rotates from off-axis mounts, and the HUD throttle/fuel percentage update.
   Reduce throttle with `F`; confirm burn rate decreases continuously.
6. Empty all valid propellant sources while thrusting. Verify the thrusters stop
   cleanly, fractional consumption does not charge a whole mB every physics
   frame, and invalid fluids are not consumed. Verify fueled tanks drain across
   multiblock controller sections only once.
7. While seated, hold `W`/`S`, `A`/`D`, and `Q`/`E` separately with a gyrodyne
   installed. Confirm pitch, yaw, and roll respond in the expected axes; release
   each key and confirm commanded rotation stops. Check gyro attitude-hold and
   redstone disengagement.
8. Observe a non-sublevel entity in a planetary dimension and confirm Northstar
   gravity still applies. Repeat for a player, mob, and projectile inside the
   vessel; verify there is no second Northstar gravity/fall-damage adjustment.
9. Configure a valid destination and allow dimension traversal. Burn upward
   through the configured boundary. Confirm the destination plot contains one
   vessel with the same orientation, velocity, inventory/tank contents, flight
   state, and passengers, and that no old source sublevel remains.
10. Repeat with an occupied destination, a destination near world/build limits,
    and a lower linked dimension. Confirm failed placement leaves the source
    vessel and passengers intact, and successful handoffs do not duplicate or
    lose blocks.
11. In a world saved before this migration, check inventories and placed blocks
    containing old `rocketnautics` titanium IDs. Verify they resolve to the
    canonical Northstar materials and that Cosmonautics alloy nugget/sheet
    recipes still work.
