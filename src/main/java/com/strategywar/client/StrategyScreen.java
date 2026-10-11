package com.strategywar.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.strategywar.Order;
import com.strategywar.UnitList;
import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import net.minecraft.client.Camera;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strategy mode. While open: the mouse cursor is free, the view comes from a detached
 * client-only camera entity, and WASD/QE/scroll move that camera instead of the player.
 */
public class StrategyScreen extends Screen {
    private static final float PITCH = 60f;       // degrees looking down
    private static final double MIN_DIST = 8, MAX_DIST = 120;

    private Marker cam;
    private double focusX, focusY, focusZ;        // ground point the camera orbits
    private float yaw;
    private double dist = 40;
    private boolean prevHideGui;
    private Component status = Component.literal("Left-click or drag to select units");
    private double dragX = -1, dragY;              // left-button press point; -1 = not dragging

    /** Selected unit ids. Static so the selection survives leaving and re-entering strategy mode. */
    private static final Set<BlockPos> SELECTED = new HashSet<>();

    public StrategyScreen() {
        super(Component.translatable("key.strategywar.toggle"));
    }

    @Override
    protected void init() {
        if (cam != null) return; // init() re-runs on window resize
        var player = minecraft.player;
        focusX = player.getX();
        focusY = player.getY();
        focusZ = player.getZ();
        yaw = player.getYRot();
        cam = EntityType.MARKER.create(minecraft.level);
        placeCamera();
        cam.setOldPosAndRot();
        minecraft.setCameraEntity(cam);
        prevHideGui = minecraft.options.hideGui;
        minecraft.options.hideGui = true; // hides hotbar, crosshair and first-person hand
    }

    @Override
    public void removed() {
        minecraft.setCameraEntity(minecraft.player);
        minecraft.options.hideGui = prevHideGui;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        cam.setOldPosAndRot(); // lets the renderer interpolate between ticks
        double speed = dist * 0.04;
        double fwd = axis(minecraft.options.keyUp, minecraft.options.keyDown);
        double side = axis(minecraft.options.keyRight, minecraft.options.keyLeft);
        double sin = Mth.sin(yaw * Mth.DEG_TO_RAD), cos = Mth.cos(yaw * Mth.DEG_TO_RAD);
        focusX += (-sin * fwd - cos * side) * speed;
        focusZ += (cos * fwd - sin * side) * speed;
        yaw += 4f * (float) (raw(GLFW.GLFW_KEY_E) - raw(GLFW.GLFW_KEY_Q));
        placeCamera();
    }

    private void placeCamera() {
        Vec3 back = Vec3.directionFromRotation(PITCH, yaw).scale(-dist);
        // Not moveTo(): that also resets the previous position, which kills between-tick interpolation.
        cam.setPos(focusX + back.x, focusY + back.y, focusZ + back.z);
        cam.setYRot(yaw);
        cam.setXRot(PITCH);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        dist = Mth.clamp(dist * (scrollY > 0 ? 0.85 : 1.15), MIN_DIST, MAX_DIST);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragX = mouseX;
            dragY = mouseY;
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return super.mouseClicked(mouseX, mouseY, button);
        BlockHitResult hit = pick(mouseX, mouseY);
        if (hit.getType() == HitResult.Type.MISS) {
            status = Component.literal("No block under cursor");
        } else {
            Order.Kind kind = hasControlDown() ? Order.Kind.ATTACK : Order.Kind.MOVE;
            sendOrder(kind, hit.getBlockPos());
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || dragX < 0) return super.mouseReleased(mouseX, mouseY, button);
        boolean box = Math.abs(mouseX - dragX) > 4 || Math.abs(mouseY - dragY) > 4;
        if (!hasShiftDown()) SELECTED.clear(); // shift adds to the selection
        BlockPos nearest = null;
        double nearestDist = 12 * 12;          // click must land within 12px of a unit marker
        for (UnitList.Unit u : UnitList.latest) {
            float[] p = toScreen(unitPos(u, 1f));
            if (p == null) continue;
            if (box) {
                if (inRange(p[0], dragX, mouseX) && inRange(p[1], dragY, mouseY)) SELECTED.add(u.id());
            } else {
                double d = (p[0] - mouseX) * (p[0] - mouseX) + (p[1] - mouseY) * (p[1] - mouseY);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = u.id();
                }
            }
        }
        if (nearest != null) SELECTED.add(nearest);
        dragX = -1;
        status = Component.literal(SELECTED.size() + " unit(s) selected");
        return true;
    }

    /**
     * Where to draw a unit this frame: its Nav Table run through the vehicle's interpolated render pose,
     * so markers stay glued to the vehicle. The server's position (4x/second) is only a fallback.
     */
    private Vector3f unitPos(UnitList.Unit u, float partialTick) {
        if (SableCompanion.INSTANCE.getContaining(minecraft.level, u.id()) instanceof ClientSubLevelAccess vehicle)
            return vehicle.renderPose(partialTick).transformPosition(Vec3.atCenterOf(u.id())).toVector3f();
        return u.pos();
    }

    private static boolean inRange(double v, double a, double b) {
        return v >= Math.min(a, b) && v <= Math.max(a, b);
    }

    private void sendOrder(Order.Kind kind, BlockPos target) {
        SELECTED.retainAll(UnitList.latest.stream().map(UnitList.Unit::id).toList()); // drop destroyed/lost units
        if (SELECTED.isEmpty()) {
            status = Component.literal("No units selected");
            return;
        }
        PacketDistributor.sendToServer(new Order(kind, target, List.copyOf(SELECTED)));
        status = Component.literal(kind + " (" + SELECTED.size() + " units)"
                + (kind == Order.Kind.CEASE_FIRE ? "" : ": " + target.toShortString()));
    }

    /** Where a world point appears on screen (GUI coordinates), or null if it's behind the camera. Inverse of pick(). */
    private float @Nullable [] toScreen(Vector3f p) {
        Camera c = minecraft.gameRenderer.getMainCamera();
        Vec3 cp = c.getPosition();
        Vector3f d = new Vector3f((float) (p.x - cp.x), (float) (p.y - cp.y), (float) (p.z - cp.z));
        float z = d.dot(c.getLookVector());
        if (z < 0.1f) return null;
        double tanHalf = Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2);
        double nx = -d.dot(c.getLeftVector()) / z / (tanHalf * width / height);
        double ny = d.dot(c.getUpVector()) / z / tanHalf;
        return new float[]{(float) ((nx + 1) / 2 * width), (float) ((1 - ny) / 2 * height)};
    }

    /** Casts a ray from the camera through the cursor and returns the block it hits. */
    private BlockHitResult pick(double mouseX, double mouseY) {
        Camera c = minecraft.gameRenderer.getMainCamera();
        double tanHalf = Math.tan(Math.toRadians(minecraft.options.fov().get()) / 2);
        double nx = (2 * mouseX / width - 1) * tanHalf * width / height;
        double ny = (1 - 2 * mouseY / height) * tanHalf;
        Vector3f look = c.getLookVector(), up = c.getUpVector(), left = c.getLeftVector();
        Vec3 dir = new Vec3(
                look.x - left.x * nx + up.x * ny,
                look.y - left.y * nx + up.y * ny,
                look.z - left.z * nx + up.z * ny).normalize();
        Vec3 from = c.getPosition();
        return minecraft.level.clip(new ClipContext(from, from.add(dir.scale(512)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (StrategyWarClient.TOGGLE.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_X) {
            sendOrder(Order.Kind.CEASE_FIRE, BlockPos.ZERO);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A && hasControlDown()) {
            UnitList.latest.forEach(u -> SELECTED.add(u.id()));
            status = Component.literal(SELECTED.size() + " unit(s) selected");
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers); // Esc closes too
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        for (UnitList.Unit u : UnitList.latest) {
            float[] p = toScreen(unitPos(u, partialTick));
            if (p == null) continue;
            int x = (int) p[0], y = (int) p[1];
            int color = SELECTED.contains(u.id()) ? 0xFF55FF55 : 0xFFFFFFFF;
            g.renderOutline(x - 6, y - 6, 12, 12, color);
            g.fill(x - 1, y - 1, x + 1, y + 1, color);
        }
        if (dragX >= 0 && (Math.abs(mouseX - dragX) > 4 || Math.abs(mouseY - dragY) > 4)) {
            int x0 = (int) Math.min(dragX, mouseX), y0 = (int) Math.min(dragY, mouseY);
            int x1 = (int) Math.max(dragX, mouseX), y1 = (int) Math.max(dragY, mouseY);
            g.fill(x0, y0, x1, y1, 0x3355FF55);
            g.renderOutline(x0, y0, x1 - x0, y1 - y0, 0xFF55FF55);
        }
        g.drawString(font, "LMB/drag select (Shift add, Ctrl+A all) | RMB move, Ctrl+RMB attack, X cease fire", 6, 6, 0xFFFFFF);
        g.drawString(font, "WASD pan, Q/E rotate, scroll zoom | G exit", 6, 16, 0xAAAAAA);
        g.drawString(font, status, 6, 28, 0xFFFF55);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // no blur/dim: the world is the game board
    }

    private int axis(KeyMapping pos, KeyMapping neg) {
        return raw(pos.getKey().getValue()) - raw(neg.getKey().getValue());
    }

    private int raw(int key) {
        return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key) ? 1 : 0;
    }
}
