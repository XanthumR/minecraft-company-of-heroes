package com.strategywar;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.List;

/**
 * Server -> client, a few times a second: the units this player commands.
 * A unit's id is its Nav Table's block position (inside the vehicle's Sable plot); pos is where it is in the world.
 */
public record UnitList(List<Unit> units) implements CustomPacketPayload {
    public record Unit(BlockPos id, Vector3f pos) {
        static final StreamCodec<ByteBuf, Unit> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Unit::id, ByteBufCodecs.VECTOR3F, Unit::pos, Unit::new);
    }

    /** Latest list received. Only written and read on the client thread. */
    public static List<Unit> latest = List.of();

    public static final Type<UnitList> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(StrategyWar.MODID, "unit_list"));
    public static final StreamCodec<ByteBuf, UnitList> STREAM_CODEC =
            Unit.STREAM_CODEC.apply(ByteBufCodecs.list()).map(UnitList::new, UnitList::units);

    @Override
    public Type<UnitList> type() {
        return TYPE;
    }
}
