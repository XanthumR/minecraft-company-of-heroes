package com.strategywar;

import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.AbstractMountedCannonContraption;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.config.DimensionMunitionPropertiesHandler;
import rbasamoyai.createbigcannons.munitions.config.components.BallisticPropertiesComponent;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Aims Create Big Cannons mounts on a unit at an attack-ground target and fires when on target.
 * The first shot is a ranging shot: we measure the real shell it fires and aim later shots with that.
 */
public final class Gunnery {
    private static final float YAW_SPEED = 3, PITCH_SPEED = 2; // turret traverse, degrees per tick
    private static final float ON_TARGET = 0.5f;               // degrees
    private static final int TRIGGER_INTERVAL = 20;            // ticks between trigger pulls; unloaded cannons just click
    private static final int SEARCH_RADIUS = 24;               // blocks from the Nav Table that count as "this unit"

    private static final class Gun {
        @Nullable Vec3 target;
        Ballistics shell = Ballistics.RANGING_GUESS;
        int cooldown;
        boolean parked; // back at neutral after cease fire; stop touching the mount
    }

    private static final Map<CannonMountBlockEntity, Gun> GUNS = new WeakHashMap<>(); // server thread only
    private static @Nullable Gun firing; // set while fireShot runs, so we can measure the shell it spawns

    private static final Method GET_BALLISTICS; // protected in CBC
    static {
        try {
            GET_BALLISTICS = AbstractCannonProjectile.class.getDeclaredMethod("getBallisticProperties");
            GET_BALLISTICS.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Create Big Cannons changed its projectile API", e);
        }
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tick());
        NeoForge.EVENT_BUS.addListener((EntityJoinLevelEvent e) -> {
            if (firing != null && e.getEntity() instanceof AbstractCannonProjectile shell) measure(shell, firing);
        });
    }

    /** Points every cannon on this unit at {@code target}, or ceases fire when null. */
    static void attack(NavTableBlockEntity nav, @Nullable Vec3 target) {
        Level level = nav.getLevel();
        BlockPos c = nav.getBlockPos();
        // Vehicle blocks live together in a Sable plot, so "this unit's cannons" = mounts near its Nav Table.
        for (int cx = (c.getX() - SEARCH_RADIUS) >> 4; cx <= (c.getX() + SEARCH_RADIUS) >> 4; cx++) {
            for (int cz = (c.getZ() - SEARCH_RADIUS) >> 4; cz <= (c.getZ() + SEARCH_RADIUS) >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                for (BlockEntity be : level.getChunk(cx, cz).getBlockEntities().values()) {
                    if (be instanceof CannonMountBlockEntity mount && mount.getBlockPos().closerThan(c, SEARCH_RADIUS)) {
                        Gun gun = GUNS.computeIfAbsent(mount, m -> new Gun());
                        gun.target = target;
                        gun.parked = false;
                    }
                }
            }
        }
    }

    private static void tick() {
        GUNS.keySet().removeIf(BlockEntity::isRemoved);
        GUNS.forEach((mount, gun) -> {
            if (!mount.isRunning() || mount.getContraption() == null) return;
            if (gun.target != null) aim(mount, gun);
            // Cease fire: traverse back to the mount's assembly orientation, same as CBC's own reset.
            else if (!gun.parked) gun.parked = turn(mount, mount.getContraptionDirection().toYRot(), 0);
        });
    }

    private static void aim(CannonMountBlockEntity mount, Gun gun) {
        var subLevel = Sable.HELPER.getContaining(mount);
        // ponytail: muzzle approximated as 2 blocks above the mount; barrel length ignored. Fine past ~30 blocks.
        Vec3 muzzle = Vec3.atCenterOf(mount.getBlockPos().above(2));
        if (subLevel != null) muzzle = subLevel.logicalPose().transformPosition(muzzle);

        Vec3 to = gun.target.subtract(muzzle);
        double dist = to.horizontalDistance();
        Double elevation = gun.shell.solve(dist, to.y);
        if (elevation == null || dist < 1) return; // out of range, or right on top of us

        // Ballistics are solved in world space (gravity is world-down), then turned into the vehicle's frame.
        double cos = Math.cos(elevation);
        Vec3 dir = new Vec3(to.x / dist * cos, Math.sin(elevation), to.z / dist * cos);
        if (subLevel != null) dir = subLevel.logicalPose().transformNormalInverse(dir);
        float wantYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z)); // Minecraft yaw: 0 = south
        float wantPitch = (float) Math.toDegrees(Math.asin(Mth.clamp(dir.y, -1, 1)));

        boolean onTarget = turn(mount, wantYaw, wantPitch);
        if (gun.cooldown > 0) gun.cooldown--;
        else if (onTarget) {
            fire(mount, gun);
            gun.cooldown = TRIGGER_INTERVAL;
        }
    }

    /** Traverses one tick's worth toward the wanted angles. Returns true once within ON_TARGET. */
    private static boolean turn(CannonMountBlockEntity mount, float wantYaw, float wantPitch) {
        float yaw = mount.getYawOffset(0), pitch = mount.getDisplayPitch();
        float yawError = Mth.wrapDegrees(wantYaw - yaw), pitchError = wantPitch - pitch;
        mount.setYaw(yaw + Mth.clamp(yawError, -YAW_SPEED, YAW_SPEED));
        mount.setPitch(pitch + Mth.clamp(pitchError, -PITCH_SPEED, PITCH_SPEED)); // mount clamps to its own limits
        mount.sendData();
        return Math.abs(yawError) < ON_TARGET && Math.abs(pitchError) < ON_TARGET;
    }

    private static void fire(CannonMountBlockEntity mount, Gun gun) {
        if (!(mount.getLevel() instanceof ServerLevel level)) return;
        var entity = mount.getContraption();
        if (!(entity.getContraption() instanceof AbstractMountedCannonContraption cannon)) return;
        firing = gun;
        try {
            cannon.fireShot(level, entity);
        } finally {
            firing = null;
        }
    }

    private static void measure(AbstractCannonProjectile shell, Gun gun) {
        double speed = shell.getDeltaMovement().length();
        if (speed < 0.1) return;
        try {
            var b = (BallisticPropertiesComponent) GET_BALLISTICS.invoke(shell);
            var dim = DimensionMunitionPropertiesHandler.getProperties(shell.level());
            gun.shell = new Ballistics(speed, b.gravity() * dim.gravityMultiplier(),
                    b.drag() * dim.dragMultiplier(), b.isQuadraticDrag());
        } catch (ReflectiveOperationException ignored) {
            // keep the previous estimate
        }
    }
}
