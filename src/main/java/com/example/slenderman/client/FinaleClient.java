package com.example.slenderman.client;

import com.example.slenderman.SlenderFinale;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The client side of the finale (see SlenderFinale on the server):
 *  TURN : the player cannot walk, the view is turned to Slenderman (2 s) and held on him, the mouse does nothing.
 *  RUN  : the heroine runs forward by herself (sprint, jumps when something stops her); the mouse still turns the view.
 *  BLACK: black screen 3 s, then it fades in for 2 s; the player cannot move.
 * The effects of the camera (flash, static layers) are called in the camera mod by reflection; without the camera mod / the camera in the hand
 * there are none.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class FinaleClient {
    /** the slow turn to him (degrees per second); the stage ends when the sanity is 100, not after a fixed time. */
    public static final float TURN_DEG_PER_SEC = 45.0F;
    private static long lastFrameNs;
    /** the server always ends the finale; if it never tells us (a lost packet) the client lets go after this. */
    private static final long SAFETY_MS = 120000L;

    private static int stage = SlenderFinale.STAGE_NONE;
    private static int slenderId;
    private static long stageStartMs, lastMessageMs;
    private static float startYaw, startPitch;

    private FinaleClient() {}

    public static boolean active() {
        return stage >= SlenderFinale.STAGE_TURN;
    }

    // ------------------------------------------------------------------ messages

    public static void onStage(int newStage, int entityId) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        int old = stage;
        stage = newStage;
        slenderId = entityId;
        lastMessageMs = now;
        if (newStage != old) stageStartMs = now;
        if (newStage == SlenderFinale.STAGE_TURN && mc.player != null) {
            startYaw = mc.player.getYRot();
            startPitch = mc.player.getXRot();
        }
        camera("setFinaleRun", newStage == SlenderFinale.STAGE_RUN);
        if (newStage == SlenderFinale.STAGE_BLACK && old != SlenderFinale.STAGE_BLACK) camera("finaleBlackFlash", null);
        if (newStage == SlenderFinale.STAGE_NONE) releaseKeys(mc);
    }

    public static void onFlash() {
        camera("finaleTeleportFlash", null);
    }

    /** calls a static method of the camera mod, if it is there (arg null = no argument). */
    private static void camera(String method, Boolean arg) {
        try {
            Class<?> c = Class.forName("com.example.slendercam.client.CameraEffects");
            if (arg == null) c.getMethod(method).invoke(null);
            else c.getMethod(method, boolean.class).invoke(null, arg);
        } catch (Throwable ignored) {
            // no camera mod: no camera effects
        }
    }

    // ------------------------------------------------------------------ input

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || stage == SlenderFinale.STAGE_NONE) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { stage = SlenderFinale.STAGE_NONE; return; }
        if (System.currentTimeMillis() - lastMessageMs > SAFETY_MS) {
            stage = SlenderFinale.STAGE_NONE;
            camera("setFinaleRun", false);
            releaseKeys(mc);
            return;
        }
        if (stage < SlenderFinale.STAGE_TURN) return;
        boolean run = stage == SlenderFinale.STAGE_RUN;
        mc.options.keyUp.setDown(run);
        mc.options.keySprint.setDown(run);
        mc.options.keyDown.setDown(false);
        mc.options.keyLeft.setDown(false);
        mc.options.keyRight.setDown(false);
        mc.options.keyShift.setDown(false);
        // she jumps by herself when something stops her (a block, a fence of one block, a hill)
        mc.options.keyJump.setDown(run && (mc.player.horizontalCollision || mc.player.isInWater()));
    }

    private static void releaseKeys(Minecraft mc) {
        if (mc == null || mc.getWindow() == null) return;
        KeyMapping[] keys = { mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight,
                mc.options.keyJump, mc.options.keySprint, mc.options.keyShift };
        long window = mc.getWindow().getWindow();
        for (KeyMapping k : keys) {
            boolean down = false;
            try {
                if (k.getKey().getType() == InputConstants.Type.KEYSYM) down = InputConstants.isKeyDown(window, k.getKey().getValue());
            } catch (Throwable ignored) {
            }
            k.setDown(down);
        }
    }

    // ------------------------------------------------------------------ the view

    /** the slow turn to him: the view moves toward him at a fixed speed (the mouse does nothing) and stays on him. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || stage != SlenderFinale.STAGE_TURN) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Entity e = mc.level.getEntity(slenderId);
        if (e == null) return;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 target = e.position().add(0.0D, e.getBbHeight() * 0.8D, 0.0D);
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        float yaw = (float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 57.29577951308232D);
        long nowNs = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0.0F : Mth.clamp((nowNs - lastFrameNs) / 1.0E9F, 0.0F, 0.1F);
        lastFrameNs = nowNs;
        float step = TURN_DEG_PER_SEC * dt;
        float ny = mc.player.getYRot() + Mth.clamp(Mth.wrapDegrees(yaw - mc.player.getYRot()), -step, step);
        float np = mc.player.getXRot() + Mth.clamp(pitch - mc.player.getXRot(), -step, step);
        mc.player.setYRot(ny);
        mc.player.setXRot(np);
        mc.player.yRotO = ny;
        mc.player.xRotO = np;
    }

    /** no pause during the finale; Escape only gives the short glitch of the camera. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGH)
    public static void onScreenOpening(net.minecraftforge.client.event.ScreenEvent.Opening event) {
        if (stage < SlenderFinale.STAGE_TURN || !(event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen)) return;
        event.setCanceled(true);
        camera("pauseGlitch", null);
        resumeSounds = true;          // Minecraft paused all the sounds for the pause we refused: they go on at the next tick
    }

    private static boolean resumeSounds;

    @SubscribeEvent
    public static void onTickResume(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !resumeSounds) return;
        resumeSounds = false;
        try {
            Minecraft.getInstance().getSoundManager().resume();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ the black screen

    @SubscribeEvent
    public static void onGuiPost(RenderGuiEvent.Post event) {
        if (stage != SlenderFinale.STAGE_BLACK) return;
        double t = (System.currentTimeMillis() - stageStartMs) / 1000.0D;
        double a = t < SlenderFinale.BLACK_SECONDS ? 1.0D
                : 1.0D - (t - SlenderFinale.BLACK_SECONDS) / SlenderFinale.FADE_SECONDS;     // the eyes open slowly
        if (a <= 0.0D) return;
        int alpha = Mth.clamp((int) Math.round(a * 255.0D), 0, 255);
        GuiComponent.fill(event.getPoseStack(), 0, 0, event.getWindow().getGuiScaledWidth(), event.getWindow().getGuiScaledHeight(), alpha << 24);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stage = SlenderFinale.STAGE_NONE;
        camera("setFinaleRun", false);
    }
}
