package com.strategywar;

import dev.simulated_team.simulated.content.blocks.nav_table.navigation_target.NavigationTarget;
import dev.simulated_team.simulated.index.SimDataComponents;
import dev.simulated_team.simulated.index.SimRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.UUID;
import java.util.function.Supplier;

@Mod(StrategyWar.MODID)
public class StrategyWar {
    public static final String MODID = "strategywar";

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    private static final DeferredRegister<NavigationTarget> NAV_TARGETS =
            DeferredRegister.create(SimRegistries.Keys.NAVIGATION_TARGET, MODID);

    /** Goes in a Nav Table; marks that vehicle as a unit you can command from strategy mode. */
    public static final DeferredItem<Item> COMMAND_ORDER =
            ITEMS.registerSimpleItem("command_order", new Item.Properties().stacksTo(1));
    /** Where the unit was last ordered to go. Stored on the order item so it survives reloads. */
    public static final Supplier<DataComponentType<BlockPos>> ORDER_POS = COMPONENTS.registerComponentType(
            "order_pos", b -> b.persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC));
    /** Player who put the order in a Nav Table. Only they can command that unit. */
    public static final Supplier<DataComponentType<UUID>> OWNER = COMPONENTS.registerComponentType(
            "owner", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    /** The vehicle's front, in its own (Sable plot) frame. Set from where the player faced when inserting the order. */
    public static final Supplier<DataComponentType<Direction>> FORWARD = COMPONENTS.registerComponentType(
            "forward", b -> b.persistent(Direction.CODEC).networkSynchronized(Direction.STREAM_CODEC));
    public static final DeferredHolder<NavigationTarget, CommandTarget> COMMAND_TARGET =
            NAV_TARGETS.register("command_order", CommandTarget::new);

    public StrategyWar(IEventBus modBus) {
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        NAV_TARGETS.register(modBus);
        // This component is how the Nav Table recognises the item as a navigation target.
        modBus.addListener((ModifyDefaultComponentsEvent e) ->
                e.modify(COMMAND_ORDER, b -> b.set(SimDataComponents.TARGET, COMMAND_TARGET.get())));
        modBus.addListener((BuildCreativeModeTabContentsEvent e) -> {
            if (e.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) e.accept(COMMAND_ORDER);
        });
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("3") // bump when packets change
                .playToServer(Order.TYPE, Order.STREAM_CODEC, (msg, ctx) -> CommandTarget.order(ctx.player(), msg))
                .playToClient(UnitList.TYPE, UnitList.STREAM_CODEC, (msg, ctx) -> UnitList.latest = msg.units()));
        CommandTarget.register();
        Gunnery.register();
    }
}
