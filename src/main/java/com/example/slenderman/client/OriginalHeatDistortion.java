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
import com.mojang.math.Vector3f;
import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
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
    private static final boolean DEBUG = false;
    private static long lastLog;

    // ---- the MASK version (the one that is used now): a rectangle in front of Slenderman, drawn on the screen ----
    /** false = the old ellipsoid drawn in the world is off (it did not show with shader packs); true = bring it back. */
    private static final boolean WORLD_STAGE = false;
    private static final float MASK_WIDTH = 8.0F;          // blocks: much wider than the figure
    private static final float MASK_BELOW = 0.4F;          // blocks below his feet
    private static final float MASK_ABOVE = 1.6F;          // blocks above his head
    private static final float MASK_FRONT = 1.5F;          // blocks in front of him (towards the player)
    private static final float MASK_MIX = 0.82F;           // how strongly the bent copy lies over the picture (the rest is the normal picture: the ghost)
    private static final float MASK_COLUMNS = 0.025F;      // wavy columns, in screen widths
    private static final float MASK_SLABS = 0.060F;        // pushed blocks
    private static final float MASK_BEND = 0.030F;         // slow bend
    private static final float MASK_SOFT = 0.04F;          // soft edge of the rectangle (0 = hard)
    private static final float MASK_BRIGHT_R = 1.75F, MASK_BRIGHT_G = 1.75F, MASK_BRIGHT_B = 1.40F;   // brighter picture (from the screenshots: x1.5 on average)
    private static final float MASK_CONTRAST = 1.65F;      // harder picture: the light parts burn out
    /** TEST: true = the rectangle is a red transparent plate (you see where it stands). */
    private static final boolean MASK_DEBUG = false;
    private static double lastFov = 70.0D;

    private static ShaderInstance maskShader;

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
            try {
                event.registerShader(
                        new ShaderInstance(event.getResourceManager(), new ResourceLocation(SlendermanMod.ID, "mask_heat"),
                                DefaultVertexFormat.POSITION_COLOR_TEX),
                        instance -> { maskShader = instance; System.out.println("[SlenderHeat] mask shader loaded"); });
            } catch (Throwable t) {
                System.out.println("[SlenderHeat] mask shader FAILED to load");
                t.printStackTrace();
                maskShader = null;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!WORLD_STAGE || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || shader == null) return;
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

    // =====================================================================================================
    // MASK VERSION: a flat rectangle stands in front of Slenderman. It is drawn on the screen after the world and before the interface
    // (the same place where the death picture and the camera copy the frame: it works with shader packs too).
    // =====================================================================================================

    /** the field of view that the game uses for the picture (so the rectangle lands on the right place). */
    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov e) {
        lastFov = e.getFOV();
    }

    @SubscribeEvent
    public static void onGuiPre(RenderGuiEvent.Pre e) {
        if (maskShader == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (DeathSequenceClient.isPortrait()) return;
        SlendermanEntity target = closest(mc);
        if (target == null) return;
        if (!MASK_DEBUG && !flickerOn()) return;
        try {
            drawMask(mc, e, target);
        } catch (Throwable t) {
            if (System.currentTimeMillis() - lastLog > 5000L) {
                lastLog = System.currentTimeMillis();
                t.printStackTrace();
            }
        }
    }

    /** corner of the rectangle: place in the view space (x right, y up, z forward) and place on the rectangle (u, v). */
    private static final class Corner {
        float x, y, z, u, v;
        Corner(float x, float y, float z, float u, float v) { this.x = x; this.y = y; this.z = z; this.u = u; this.v = v; }
    }

    private static void drawMask(Minecraft mc, RenderGuiEvent.Pre e, SlendermanEntity target) {
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 eye = cam.getPosition();
        Vec3 pos = target.getPosition(e.getPartialTick());

        // the rectangle: stands in front of him (towards the player), turned to the player
        double dx = eye.x - pos.x, dz = eye.z - pos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.05D) return;
        dx /= len;
        dz /= len;
        double front = Math.min(MASK_FRONT, len * 0.45D);
        double cxw = pos.x + dx * front, czw = pos.z + dz * front;
        double px = dz, pz = -dx;                               // along the rectangle (horizontal, across the view)
        double yTop = pos.y + target.getBbHeight() + MASK_ABOVE, yBot = pos.y - MASK_BELOW;
        double hw = MASK_WIDTH * 0.5D;

        // is any part of it seen (not hidden by blocks)? look at the middle at three heights
        boolean seen = false;
        for (int i = 0; i < 3 && !seen; i++) {
            double yy = Mth.lerp(0.2D + 0.3D * i, yBot, yTop);
            Vec3 pt = new Vec3(cxw, yy, czw);
            ClipContext cc = new ClipContext(eye, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player);
            if (mc.level.clip(cc).getType() == HitResult.Type.MISS) seen = true;
        }
        if (!seen) return;

        Vector3f look = cam.getLookVector(), up = cam.getUpVector(), left = cam.getLeftVector();
        double[][] w = {
                {cxw - px * hw, yTop, czw - pz * hw, 0, 0},
                {cxw + px * hw, yTop, czw + pz * hw, 1, 0},
                {cxw + px * hw, yBot, czw + pz * hw, 1, 1},
                {cxw - px * hw, yBot, czw - pz * hw, 0, 1}};
        java.util.ArrayList<Corner> poly = new java.util.ArrayList<>();
        for (double[] c : w) {
            double rx = c[0] - eye.x, ry = c[1] - eye.y, rz = c[2] - eye.z;
            float vx = (float) -(rx * left.x() + ry * left.y() + rz * left.z());
            float vy = (float) (rx * up.x() + ry * up.y() + rz * up.z());
            float vz = (float) (rx * look.x() + ry * look.y() + rz * look.z());
            poly.add(new Corner(vx, vy, vz, (float) c[3], (float) c[4]));
        }
        // cut off what is behind the camera
        final float near = 0.05F;
        java.util.ArrayList<Corner> clipped = new java.util.ArrayList<>();
        for (int i = 0; i < poly.size(); i++) {
            Corner a = poly.get(i), b = poly.get((i + 1) % poly.size());
            boolean ain = a.z >= near, bin = b.z >= near;
            if (ain) clipped.add(a);
            if (ain != bin) {
                float k = (near - a.z) / (b.z - a.z);
                clipped.add(new Corner(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, near, a.u + (b.u - a.u) * k, a.v + (b.v - a.v) * k));
            }
        }
        if (clipped.size() < 3) return;

        int guiW = e.getWindow().getGuiScaledWidth(), guiH = e.getWindow().getGuiScaledHeight();
        float tanHalf = (float) Math.tan(Math.toRadians(lastFov) * 0.5D);
        float aspect = (float) guiW / (float) guiH;

        RenderTarget main = mc.getMainRenderTarget();
        int tw = main.width, th = main.height;
        if (tw <= 0 || th <= 0) return;
        if (grab == null) {
            grab = new TextureTarget(tw, th, false, Minecraft.ON_OSX);
            grab.setFilterMode(GL11.GL_LINEAR);
        } else if (grab.width != tw || grab.height != th) {
            grab.resize(tw, th, Minecraft.ON_OSX);
        }
        // copy of the frame (the world is finished, the interface is not drawn yet)
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, grab.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, tw, th, 0, 0, tw, th, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        main.bindWrite(false);

        long slot = System.nanoTime() / FLICKER_SLOT_NANOS;
        long hh = slot * 0x9E3779B97F4A7C15L;
        hh ^= (hh >>> 31);
        float seed = ((hh >>> 8) & 0xFFFL) / 4096.0F * 8.0F;          // every flash looks different
        float time = (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000.0F;

        Matrix4f m = e.getPoseStack().last().pose();
        try {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.colorMask(true, true, true, false);

            RenderSystem.setShader(() -> maskShader);
            RenderSystem.setShaderTexture(0, grab.getColorTextureId());
            RenderSystem.setShaderTexture(1, NOISE_TEX);
            maskShader.safeGetUniform("ScreenSize").set((float) tw, (float) th);
            maskShader.safeGetUniform("Params").set(time, seed, MASK_DEBUG ? 1.0F : 0.0F, MASK_MIX);
            maskShader.safeGetUniform("Gain").set(MASK_BRIGHT_R, MASK_BRIGHT_G, MASK_BRIGHT_B, MASK_CONTRAST);
            maskShader.safeGetUniform("Warp").set(MASK_COLUMNS, MASK_SLABS, MASK_BEND, MASK_SOFT);

            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR_TEX);
            for (int i = 1; i < clipped.size() - 1; i++) {
                maskVert(b, m, clipped.get(0), tanHalf, aspect, guiW, guiH);
                maskVert(b, m, clipped.get(i), tanHalf, aspect, guiW, guiH);
                maskVert(b, m, clipped.get(i + 1), tanHalf, aspect, guiW, guiH);
            }
            BufferUploader.drawWithShader(b.end());
        } finally {
            main.bindWrite(false);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static void maskVert(BufferBuilder b, Matrix4f m, Corner c, float tanHalf, float aspect, int guiW, float guiH) {
        float ndcX = c.x / (c.z * tanHalf * aspect);
        float ndcY = c.y / (c.z * tanHalf);
        float gx = (ndcX * 0.5F + 0.5F) * guiW;
        float gy = (0.5F - ndcY * 0.5F) * guiH;
        b.vertex(m, gx, gy, 0.0F).color(1.0F, 1.0F, 1.0F, 1.0F).uv(c.u, c.v).endVertex();
    }
}
