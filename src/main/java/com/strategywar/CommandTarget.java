package com.strategywar;

import dev.ryanhcode.sable.Sable;
import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity;
import dev.simulated_team.simulated.content.blocks.nav_table.navigation_target.NavigationTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Nav Table target for the Command Order item. The {@link Driver} decides how to drive; this maps
 * its outputs onto the Nav Table's sides relative to the vehicle's front:
 * left/right = steering, back = halt (15 = stop), front = reverse (15 = reverse, for a Gearshift).
 */
public class CommandTarget implements NavigationTarget {
    /** Every Nav Table currently holding a Command Order. Server thread only. */
    private static final Set<NavTableBlockEntity> UNITS = Collections.newSetFromMap(new WeakHashMap<>());

    private static final class Drive {
        final Driver driver = new Driver();
        @Nullable BlockPos target;
    }

    private static final Map<NavTableBlockEntity, Drive> DRIVES = new WeakHashMap<>(); // server thread only

    @Override
    public @Nullable Vec3 getTarget(NavTableBlockEntity nav, ItemStack self) {
        // The Nav Table calls this every server tick, so it doubles as unit registration.
        UNITS.add(nav);
        BlockPos pos = self.get(StrategyWar.ORDER_POS);
        // No order = target ourselves, so we land in the deadzone and output the halt signal.
        Vec3 target = pos == null ? nav.getProjectedSelfPos() : Vec3.atCenterOf(pos);
        Direction forward = self.get(StrategyWar.FORWARD);
        if (forward != null) drive(nav, forward, pos, target); // once per tick, so the driver's timers count ticks
        return target;
    }

    private static void drive(NavTableBlockEntity nav, Direction forward, @Nullable BlockPos pos, Vec3 target) {
        Drive d = DRIVES.computeIfAbsent(nav, n -> new Drive());
        if (!Objects.equals(d.target, pos)) {
            d.target = pos;
            d.driver.retarget();
        }
        Vec3 local = target.subtract(nav.getProjectedSelfPos());
        var vehicle = Sable.HELPER.getContaining(nav);
        if (vehicle != null) local = vehicle.logicalPose().transformNormalInverse(local); // world -> vehicle frame
        double ahead = local.dot(Vec3.atLowerCornerOf(forward.getNormal()));
        double left = local.dot(Vec3.atLowerCornerOf(forward.getCounterClockWise().getNormal()));
        d.driver.tick(Math.atan2(left, ahead), Math.hypot(ahead, left));
    }

    @Override
    public float getDeadzone() {
        return 4; // "arrived" radius
    }

    /**
     * 15 means stop, to match Analog Transmissions and Offroad wheel brakes (both stop at 15).
     * The back side is the throttle: 0 (full speed) while the target is anywhere ahead, rising as
     * it swings behind. Arrived or no order: every side outputs 15. Left/right then cancel out.
     */
    @Override
    public int getRedstoneStrength(NavTableBlockEntity nav, Direction direction, ItemStack self) {
        // The client also asks (to render signals). Driver state belongs to the server thread, and in
        // single-player both threads share these static maps, so the client must not touch them.
        if (nav.getLevel().isClientSide) return 0;
        Direction forward = self.get(StrategyWar.FORWARD);
        Drive d = DRIVES.get(nav);
        if (forward != null && d != null) {
            Direction side = direction.getOpposite(); // Minecraft passes the direction from the neighbour toward us
            if (side == forward) return d.driver.reverse;
            if (side == forward.getOpposite()) return d.driver.halt;
            if (side == forward.getCounterClockWise()) return d.driver.steerLeft;
            if (side == forward.getClockWise()) return d.driver.steerRight;
            return 0;
        }
        // Legacy orders (inserted before the front was recorded): plain point-at-target steering.
        Vec3 toTarget = nav.getTargetPosition(true).subtract(nav.getProjectedSelfPos());
        if (toTarget.horizontalDistance() < getDeadzone()) return 15;
        // Simulated's side math goes negative (down to -14) for sides facing away from the target.
        return Math.max(0, calculateSideStrength(nav, direction, self));
    }

    @Override
    public void onInsert(ItemStack stack, NavTableBlockEntity nav, @Nullable Player player) {
        // Simulated calls this on both sides; the server's stack syncs to the client anyway.
        if (player == null || nav.getLevel().isClientSide) return;
        stack.set(StrategyWar.OWNER, player.getUUID());
        // The way the player faces when inserting becomes the vehicle's front, snapped to a block side.
        Vec3 look = player.getLookAngle();
        var vehicle = Sable.HELPER.getContaining(nav);
        if (vehicle != null) look = vehicle.logicalPose().transformNormalInverse(look);
        Direction forward = Direction.getNearest(look.x, 0, look.z);
        stack.set(StrategyWar.FORWARD, forward);
        DRIVES.remove(nav);
        player.displayClientMessage(Component.literal("Unit front set to where you're facing (" + forward.getName() + ")"), true);
    }

    // ponytail: orders without an owner (inserted by hopper/deployer) obey anyone. Lock down if that gets abused.
    private static boolean commands(Player player, ItemStack stack) {
        UUID owner = stack.get(StrategyWar.OWNER);
        return stack.is(StrategyWar.COMMAND_ORDER) && (owner == null || owner.equals(player.getUUID()));
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            if (e.getServer().getTickCount() % 5 != 0) return;
            UNITS.removeIf(nav -> nav.isRemoved() || !nav.getHeldItem().is(StrategyWar.COMMAND_ORDER));
            for (ServerPlayer player : e.getServer().getPlayerList().getPlayers()) {
                List<UnitList.Unit> mine = new ArrayList<>();
                for (NavTableBlockEntity nav : UNITS) {
                    if (nav.getLevel() == player.level() && commands(player, nav.getHeldItem()))
                        mine.add(new UnitList.Unit(nav.getBlockPos(), nav.getProjectedSelfPos().toVector3f(), status(nav)));
                }
                PacketDistributor.sendToPlayer(player, new UnitList(mine));
            }
        });
    }

    private static String status(NavTableBlockEntity nav) {
        Drive d = DRIVES.get(nav);
        if (!nav.getHeldItem().has(StrategyWar.FORWARD) || d == null)
            return "! No front set: re-insert the order while facing the vehicle's front";
        Driver v = d.driver;
        return v.mode + "  L" + v.steerLeft + " R" + v.steerRight + "  throttle " + v.halt + "  reverse " + v.reverse;
    }

    static void order(Player player, Order order) {
        Level level = player.level();
        for (BlockPos id : order.units()) {
            // Ids come from the client: never load chunks for them, and re-check ownership.
            if (!level.isLoaded(id) || !(level.getBlockEntity(id) instanceof NavTableBlockEntity nav)) continue;
            ItemStack stack = nav.getHeldItem();
            if (!UNITS.contains(nav) || !commands(player, stack)) continue;
            switch (order.kind()) {
                case MOVE -> {
                    stack.set(StrategyWar.ORDER_POS, order.target());
                    nav.setChanged();
                }
                case ATTACK -> Gunnery.attack(nav, Vec3.atCenterOf(order.target()));
                case CEASE_FIRE -> Gunnery.attack(nav, null);
            }
        }
    }
}
