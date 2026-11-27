package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector4f;
import net.minecraft.util.Mth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The "HeatDistortion" shader of the original game, written again for Minecraft 1.19.2.
 * A transparent ellipsoid around Slenderman copies the frame (the GrabPass of the original) and draws it back bent by a moving noise.
 * It works on whatever framebuffer is bound at that moment (also with a shader pack), not on the vanilla main target only.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class OriginalHeatDistortion {
    // ---- the original properties of the shader ----
    private static final float STRENGTH = 0.2F;       // original range 0.1 .. 0.3
    private static final float TRANSPARENCY = 0.05F;  // original range 0.01 .. 0.1
    private static final float TILE_U = 3.0F;         // noise tiling around the body (a whole number, so there is no seam)
    private static final float TILE_V = 2.0F;         // noise tiling from top to bottom

    // ---- measured on the video of the original (30 fps) ----
    private static final float FLICKER_CHANCE = 0.29F;   // the effect is on in ~29 % of the frames, in bursts of 1-3 frames
    private static final long FLICKER_SLOT_NANOS = 33_333_333L;   // one frame of the original
    private static final float WARP_GAIN = 2.5F;         // extra bend of the picture (1.0 = the formula only; raise it for stronger waves)
    private static final float GAIN_R = 1.8F, GAIN_G = 1.7F, GAIN_B = 1.3F;   // in the effect the picture is ~1.7x brighter, less in blue

    // ---- the ellipsoid around Slenderman (blocks) ----
    private static final float RADIUS_XZ = 1.6F;
    private static final float RADIUS_Y = 2.0F;
    private static final double MAX_DISTANCE = 70.0D;
    private static final int SLICES = 32;
    private static final int STACKS = 20;

    private static final ResourceLocation NOISE_TEX = new ResourceLocation(SlendermanMod.ID, "textures/effect/heat_noise.png");

    /** TEST MODE: true = the ellipsoid is drawn as a red transparent ball and the log (latest.log) says what happens. Set false when done. */
    private static final boolean DEBUG = true;
    private static long lastLog;

    private static ShaderInstance shader;
    private static RenderTarget grab;

    private OriginalHeatDistortion() {}

    @Mod.EventBusSubscriber(modid = SlendermanMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registry {
        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            try {
                event.registerShader(
                        new ShaderInstance(event.getResourceManager(), new ResourceLocation(SlendermanMod.ID, "original_heat"),
                                DefaultVertexFormat.POSITION_COLOR_TEX),
                        instance -> { shader = instance; if (DEBUG) System.out.println("[SlenderHeat] shader loaded"); });
            } catch (Throwable t) {
                System.out.println("[SlenderHeat] shader FAILED to load");
                t.printStackTrace();
                shader = null;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || shader == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        SlendermanEntity target = closest(mc);
        if (target == null) return;
        if (DEBUG && System.currentTimeMillis() - lastLog > 3000L) {
            lastLog = System.currentTimeMillis();
            System.out.println("[SlenderHeat] drawing, distance " + Math.round(target.distanceTo(mc.player)) + ", fbo " + 0);
        }
        if (!DEBUG && !flickerOn()) return;
        try {
            draw(mc, event, target);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /** on / off in steps of one frame of the original, random, ~29 % on. */
    private static boolean flickerOn() {
        long slot = System.nanoTime() / FLICKER_SLOT_NANOS;
        long h = slot * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 32);
        return ((h & 0xFFFFL) / 65536.0F) < FLICKER_CHANCE;
    }

    private static SlendermanEntity closest(Minecraft mc) {
        SlendermanEntity best = null;
        double bestSq = MAX_DISTANCE * MAX_DISTANCE;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SlendermanEntity s) {
                if (s.isInvisible() || s.isTeleportHidden() || s.isWatcher() || !s.isAlive() || s.getDeathPhase() >= 2) continue;
                double d = s.distanceToSqr(mc.player);
                if (d < bestSq) { bestSq = d; best = s; }
            }
        }
        return best;
    }

    private static void draw(Minecraft mc, RenderLevelStageEvent event, SlendermanEntity target) {
        // the same way as the version that worked with shader packs: the main target, the matrices of the game stay untouched,
        // the pose of the camera is applied to the vertices here
        RenderTarget main = mc.getMainRenderTarget();
        int w = main.width, h = main.height;
        if (w <= 0 || h <= 0) return;

        if (grab == null) {
            grab = new TextureTarget(w, h, false, Minecraft.ON_OSX);
            grab.setFilterMode(GL11.GL_LINEAR);
        } else if (grab.width != w || grab.height != h) {
            grab.resize(w, h, Minecraft.ON_OSX);
        }

        // GrabPass: copy the frame
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, grab.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        main.bindWrite(false);

        Vec3 cam = event.getCamera().getPosition();
        Vec3 pos = target.getPosition(event.getPartialTick());
        float cx = (float) (pos.x - cam.x);
        float cy = (float) (pos.y + target.getBbHeight() * 0.5D - cam.y);
        float cz = (float) (pos.z - cam.z);
        float time = (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000.0F;
        Matrix4f m = event.getPoseStack().last().pose();

        try {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();                 // seen from inside too (when Slenderman is very close)
            RenderSystem.colorMask(true, true, true, false);

            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, grab.getColorTextureId());
            RenderSystem.setShaderTexture(1, NOISE_TEX);
            shader.safeGetUniform("ScreenSize").set((float) w, (float) h);
            shader.safeGetUniform("Params").set(STRENGTH, TRANSPARENCY, time, DEBUG ? 1.0F : 0.0F);
            shader.safeGetUniform("Tiling").set(TILE_U, TILE_V);
            shader.safeGetUniform("Gain").set(GAIN_R, GAIN_G, GAIN_B, WARP_GAIN);

            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            for (int j = 0; j < STACKS; j++) {
                float v0 = (float) j / STACKS, v1 = (float) (j + 1) / STACKS;
                double p0 = Math.PI * v0, p1 = Math.PI * v1;
                for (int i = 0; i < SLICES; i++) {
                    float u0 = (float) i / SLICES, u1 = (float) (i + 1) / SLICES;
                    double t0 = 2.0D * Math.PI * u0, t1 = 2.0D * Math.PI * u1;
                    vert(b, m, cx, cy, cz, p0, t0, u0, v0);
                    vert(b, m, cx, cy, cz, p0, t1, u1, v0);
                    vert(b, m, cx, cy, cz, p1, t1, u1, v1);
                    vert(b, m, cx, cy, cz, p1, t0, u0, v1);
                }
            }
            BufferUploader.drawWithShader(b.end());
        } finally {
            main.bindWrite(false);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    /** a vertex of the ellipsoid; the normal (in the space of the camera) travels in the colour. */
    private static void vert(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, double phi, double theta, float u, float v) {
        float sp = (float) Math.sin(phi), cp = (float) Math.cos(phi);
        float sx = sp * (float) Math.cos(theta), sy = cp, sz = sp * (float) Math.sin(theta);
        float nx = sx / RADIUS_XZ, ny = sy / RADIUS_Y, nz = sz / RADIUS_XZ;
        float inv = 1.0F / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        Vector4f n = new Vector4f(nx * inv, ny * inv, nz * inv, 0.0F);
        n.transform(m);
        b.vertex(m, cx + sx * RADIUS_XZ, cy + sy * RADIUS_Y, cz + sz * RADIUS_XZ)
                .color(Mth.clamp(n.x() * 0.5F + 0.5F, 0.0F, 1.0F), Mth.clamp(n.y() * 0.5F + 0.5F, 0.0F, 1.0F),
                        Mth.clamp(n.z() * 0.5F + 0.5F, 0.0F, 1.0F), 1.0F)
                .uv(u, v).endVertex();
    }
}
