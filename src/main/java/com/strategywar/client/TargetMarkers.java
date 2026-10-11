package com.strategywar.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Rings drawn on the ground where you gave an order, while in strategy mode.
 * Move: green ring that expands and fades out. Attack: pulsing red ring that stays until cease fire.
 */
public final class TargetMarkers {
    private record Marker(Vec3 pos, long startMs) {}

    private static final long MOVE_MS = 1500;
    private static final int SEGMENTS = 32;

    private static @Nullable Marker move, attack;

    static void move(BlockPos target) {
        move = new Marker(Vec3.upFromBottomCenterOf(target, 1), Util.getMillis());
    }

    static void attack(BlockPos target) {
        attack = new Marker(Vec3.upFromBottomCenterOf(target, 1), Util.getMillis());
    }

    static void ceaseFire() {
        attack = null;
    }

    static void render(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!(Minecraft.getInstance().screen instanceof StrategyScreen)) return;
        long now = Util.getMillis(); // wall clock, not ticks, so the animation is smooth at any frame rate
        if (move != null && now - move.startMs > MOVE_MS) move = null;
        if (move == null && attack == null) return;

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.debugQuads());
        Vec3 cam = e.getCamera().getPosition();
        PoseStack ps = e.getPoseStack();
        if (move != null) {
            float t = (now - move.startMs) / (float) MOVE_MS;
            ring(vc, ps, cam, move.pos, 0.5f + t, 0.15f, alpha(1 - t) | 0x55FF55);
        }
        if (attack != null) {
            float pulse = Mth.sin((now - attack.startMs) / 150f);
            ring(vc, ps, cam, attack.pos, 1.5f + 0.2f * pulse, 0.2f, 0xCCFF4040);
            ring(vc, ps, cam, attack.pos, 0.4f, 0.4f, 0xCCFF4040);
        }
        buffers.endBatch(RenderType.debugQuads());
    }

    private static int alpha(float a) {
        return (int) (Mth.clamp(a, 0, 1) * 255) << 24;
    }

    /** Flat ring lying on the ground at {@code center}, drawn relative to the camera to avoid float jitter far from 0,0. */
    private static void ring(VertexConsumer vc, PoseStack ps, Vec3 cam, Vec3 center, float radius, float width, int argb) {
        ps.pushPose();
        ps.translate(center.x - cam.x, center.y - cam.y + 0.05, center.z - cam.z); // lift off the block top to avoid z-fighting
        PoseStack.Pose pose = ps.last();
        float in = radius - width / 2, out = radius + width / 2;
        for (int i = 0; i < SEGMENTS; i++) {
            float a0 = i * Mth.TWO_PI / SEGMENTS, a1 = (i + 1) * Mth.TWO_PI / SEGMENTS;
            float c0 = Mth.cos(a0), s0 = Mth.sin(a0), c1 = Mth.cos(a1), s1 = Mth.sin(a1);
            // Both windings, so the ring shows whichever way it's culled.
            vc.addVertex(pose, c0 * in, 0, s0 * in).setColor(argb);
            vc.addVertex(pose, c1 * in, 0, s1 * in).setColor(argb);
            vc.addVertex(pose, c1 * out, 0, s1 * out).setColor(argb);
            vc.addVertex(pose, c0 * out, 0, s0 * out).setColor(argb);
            vc.addVertex(pose, c0 * out, 0, s0 * out).setColor(argb);
            vc.addVertex(pose, c1 * out, 0, s1 * out).setColor(argb);
            vc.addVertex(pose, c1 * in, 0, s1 * in).setColor(argb);
            vc.addVertex(pose, c0 * in, 0, s0 * in).setColor(argb);
        }
        ps.popPose();
    }
}
