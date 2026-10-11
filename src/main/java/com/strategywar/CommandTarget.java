package com.strategywar;

import dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity;
import dev.simulated_team.simulated.content.blocks.nav_table.navigation_target.NavigationTarget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Nav Table target for the Command Order item. The Nav Table does the steering: it outputs
 * redstone on the side facing the target, which the vehicle's wheels are wired to.
 */
public class CommandTarget implements NavigationTarget {
    /** Every Nav Table currently holding a Command Order. Server thread only. */
    private static final Set<NavTableBlockEntity> UNITS = Collections.newSetFromMap(new WeakHashMap<>());

    @Override
    public @Nullable Vec3 getTarget(NavTableBlockEntity nav, ItemStack self) {
        // The Nav Table calls this every server tick, so it doubles as unit registration.
        UNITS.add(nav);
        BlockPos pos = self.get(StrategyWar.ORDER_POS);
        // No order = target ourselves, so we land in the deadzone and output the halt signal.
        return pos == null ? nav.getProjectedSelfPos() : Vec3.atCenterOf(pos);
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
        Vec3 toTarget = nav.getTargetPosition(true).subtract(nav.getProjectedSelfPos());
        if (toTarget.horizontalDistance() < getDeadzone()) return 15;
        // Simulated's side math goes negative (down to -14) for sides facing away from the target.
        return Math.max(0, calculateSideStrength(nav, direction, self));
    }

    @Override
    public void onInsert(ItemStack stack, NavTableBlockEntity nav, @Nullable Player player) {
        if (player != null) stack.set(StrategyWar.OWNER, player.getUUID());
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
                        mine.add(new UnitList.Unit(nav.getBlockPos(), nav.getProjectedSelfPos().toVector3f()));
                }
                PacketDistributor.sendToPlayer(player, new UnitList(mine));
            }
        });
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
