package com.strategywar;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Client -> server: an order for the selected units, identified by their Nav Table position
 * (see {@link UnitList}). {@code target} is ignored for CEASE_FIRE.
 */
public record Order(Kind kind, BlockPos target, List<BlockPos> units) implements CustomPacketPayload {
    public enum Kind { MOVE, ATTACK, CEASE_FIRE }

    public static final Type<Order> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StrategyWar.MODID, "order"));
    public static final StreamCodec<ByteBuf, Order> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), Order::kind,
            BlockPos.STREAM_CODEC, Order::target,
            BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(256)), Order::units,
            Order::new);

    @Override
    public Type<Order> type() {
        return TYPE;
    }
}
