package com.example.slenderman.client;

import com.example.slenderman.SlenderPageEntity;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
import com.mojang.math.Vector4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * THE OUTLINE OF THE PAGES (CLIENT ONLY). When the player is closer than DIST blocks to a page (and sees it), the page gets a soft, cool
 * white glow ALONG ITS CONTOUR. The inside of the page is not touched.
 *
 * It is the idea of the "Highlighting System" of the original (its Composite shader): the silhouette of the object is blurred and the
 * blurred picture is added to the game picture everywhere EXCEPT inside the silhouette (result = picture + blurred * (1 - silhouette) * 2).
 * For a flat page that is easy to do without any extra buffer: the four corners of the sheet are projected to the screen, and around that
 * outline a ring is drawn whose brightness falls off like the edge of a blurred silhouette (2 * (1 - normal distribution) = erfc). The ring is
 * added to the picture (additive blending). The glow fades in and out smoothly, and it is hidden when a block stands between the eye and the page.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class PageHighlight {
    // ---------------------------------------------------------------- settings
    /** the glow starts when the page is closer than this (blocks, from the eyes to the middle of the sheet). */
    private static final double DIST = 3.0D;
    /** the glow grows over the last part of the way (blocks), so it does not pop on at exactly DIST. */
    private static final float FADE_RANGE = 0.5F;
    /** how soft / wide the glow is: the "blur radius" (sigma) as a share of the screen height. Bigger = wider and softer. */
    private static final float SIGMA = 0.0060F;
    /** how bright the glow is at the very edge of the page (1 = as strong as the original formula, > 1 = stronger). */
    private static final float GAIN = 0.95F;
    /** the colour: very cool white (a slight blue tint). */
    private static final float COL_R = 0.80F, COL_G = 0.91F, COL_B = 1.0F;
    /** how fast the glow fades in / out (per second). */
    private static final float FADE_SPEED = 10F;

    /** the size of the sheet, the same as in SlenderPageRenderer. */
    private static final float HALF_W = 0.27F, HALF_H = 0.375F;

    private static final int RINGS = 10;          // steps of the falloff
    private static final int ARC = 6;             // segments of the rounded corner

    // ---------------------------------------------------------------- state
    private static final class Cand {
        final SlenderPageEntity page;
        boolean visible;
        double dist;
        Cand(SlenderPageEntity page) { this.page = page; }
    }

    private static final List<Cand> CANDS = new ArrayList<>();
    private static final Map<Integer, Float> LEVEL = new HashMap<>();
    private static Matrix4f view;
    private static Matrix4f proj;
    private static Vec3 camPos = Vec3.ZERO;
    private static long lastNs;

    private PageHighlight() {}

    // ---------------------------------------------------------------- which pages are near and in sight (20 times a second)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        CANDS.clear();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) {
            LEVEL.clear();
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        for (Entity en : mc.level.entitiesForRendering()) {
            if (!(en instanceof SlenderPageEntity pg) || pg.isRemoved()) continue;
            Vec3 c = pg.position();
            double d = eye.distanceTo(c);
            if (d > DIST + 0.5D) continue;
            Cand cand = new Cand(pg);
            cand.dist = d;
            Direction f = pg.getFace();
            Vec3 n = new Vec3(f.getStepX(), f.getStepY(), f.getStepZ());
            boolean front = n.dot(eye.subtract(c)) > 0.0D;           // we look at the printed side, not at the back
            Vec3 target = c.add(n.scale(0.03D));
            BlockHitResult hit = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            cand.visible = front && (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) < 0.04D);
            CANDS.add(cand);
        }
        LEVEL.keySet().removeIf(id -> CANDS.stream().noneMatch(c -> c.page.getId() == id));
    }

    // ---------------------------------------------------------------- the camera of the world (every frame)
    @SubscribeEvent
    public static void onLevelStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        view = e.getPoseStack().last().pose().copy();
        proj = e.getProjectionMatrix().copy();
        camPos = e.getCamera().getPosition();
    }

    // ---------------------------------------------------------------- the glow (first thing the interface draws)
    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!e.getOverlay().id().equals(VanillaGuiOverlay.VIGNETTE.id())) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        float dt = lastNs == 0L ? 0.016F : Math.min(0.1F, (now - lastNs) / 1e9F);
        lastNs = now;
        if (CANDS.isEmpty() || view == null || proj == null || mc.options.hideGui) return;

        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        boolean prepared = false;
        try {
            for (Cand c : CANDS) {
                if (c.page.isRemoved()) continue;
                float near = Mth.clamp((float) (DIST - c.dist) / FADE_RANGE, 0F, 1F);
                float goal = c.visible ? near * near * (3F - 2F * near) : 0F;
                float lv = LEVEL.getOrDefault(c.page.getId(), 0F);
                lv += (goal - lv) * Math.min(1F, dt * FADE_SPEED);
                if (Math.abs(goal - lv) < 0.002F) lv = goal;
                LEVEL.put(c.page.getId(), lv);
                if (lv < 0.01F) continue;

                float[] poly = projectSheet(c.page, gw, gh);
                if (poly == null) continue;
                if (!prepared) {
                    prepared = true;
                    RenderSystem.enableBlend();
                    RenderSystem.blendFunc(com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);          // added to the picture
                    RenderSystem.disableCull();
                    RenderSystem.setShader(GameRenderer::getPositionColorShader);
                    // the hand is in front of the page: its silhouette is written to the depth buffer first (no colour), the glow is
                    // drawn behind it (farther), so it never goes over the flashlight hand
                    RenderSystem.enableDepthTest();
                    RenderSystem.depthFunc(515);
                    maskHand(e.getPoseStack().last().pose(), gw, gh);
                    RenderSystem.depthMask(false);
                }
                drawGlow(e.getPoseStack().last().pose(), poly, lv * GAIN, SIGMA * gh);
            }
        } catch (Throwable ignored) {
            // never break the game because of a graphics detail
        } finally {
            if (prepared) {
                RenderSystem.defaultBlendFunc();
                RenderSystem.depthMask(true);
                RenderSystem.colorMask(true, true, true, true);
                RenderSystem.enableDepthTest();
                RenderSystem.enableCull();
                RenderSystem.disableBlend();
                RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            }
        }
    }

    // ---------------------------------------------------------------- the sheet on the screen

    /** the four corners of the sheet in gui coordinates (x0, y0, x1, y1, ...), or null when one of them is behind the camera / it is too small. */
    private static float[] projectSheet(SlenderPageEntity pg, int gw, int gh) {
        // the same turns as SlenderPageRenderer: the face of the block, then the small tilt of the sheet around its own axis
        Direction face = pg.getFace();
        Quaternion q;
        switch (face) {
            case UP -> q = Vector3f.XP.rotationDegrees(-90.0F);
            case DOWN -> q = Vector3f.XP.rotationDegrees(90.0F);
            default -> q = Vector3f.YP.rotationDegrees(-face.toYRot());
        }
        q.mul(Vector3f.ZP.rotationDegrees(pg.getRoll()));
        Vec3 c = pg.position();
        float[][] corner = {{-HALF_W, -HALF_H}, {HALF_W, -HALF_H}, {HALF_W, HALF_H}, {-HALF_W, HALF_H}};
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            Vector3f v = new Vector3f(corner[i][0], corner[i][1], 0F);
            v.transform(q);
            Vector4f p = new Vector4f((float) (c.x + v.x() - camPos.x), (float) (c.y + v.y() - camPos.y), (float) (c.z + v.z() - camPos.z), 1F);
            p.transform(view);
            p.transform(proj);
            if (p.w() < 0.05F) return null;
            out[i * 2] = (p.x() / p.w() * 0.5F + 0.5F) * gw;
            out[i * 2 + 1] = (1F - (p.y() / p.w() * 0.5F + 0.5F)) * gh;
        }
        float area = 0F;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += out[i * 2] * out[j * 2 + 1] - out[j * 2] * out[i * 2 + 1];
        }
        if (Math.abs(area) < 8F) return null;                          // smaller than a few pixels
        return out;
    }

    /** erfc(x / sqrt(2)): 1 at the edge of the silhouette, falling like the edge of a blurred shape (twice (1 - normal distribution)). */
    private static float edgeFalloff(float z) {
        if (z <= 0F) return 1F;
        float x = z * 0.70710678F;
        float t = 1F / (1F + 0.3275911F * x);
        float poly = t * (0.254829592F + t * (-0.284496736F + t * (1.421413741F + t * (-1.453152027F + t * 1.061405429F))));
        return Mth.clamp(poly * (float) Math.exp(-x * x), 0F, 1F);     // erfc(x)
    }

    /** the ring around the outline of the sheet: the glow from its edge outwards, nothing inside. */
    private static void drawGlow(Matrix4f m, float[] poly, float strength, float sigma) {
        float area = 0F;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            area += poly[i * 2] * poly[j * 2 + 1] - poly[j * 2] * poly[i * 2 + 1];
        }
        float sgn = area > 0F ? 1F : -1F;
        // the outward angle of the normal of every edge
        float[] ang = new float[4];
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            float dx = poly[j * 2] - poly[i * 2], dy = poly[j * 2 + 1] - poly[i * 2 + 1];
            ang[i] = (float) Math.atan2(-sgn * dx, sgn * dy);          // outward normal: (dy, -dx) for a positive area
        }
        int n = 4 * (ARC + 1);
        float reach = sigma * 3.0F;
        float start = -0.7F;                                           // a little inside the edge: no seam between the sheet and the glow
        float[][] px = new float[RINGS + 1][n], py = new float[RINGS + 1][n];
        float[] al = new float[RINGS + 1];
        for (int k = 0; k <= RINGS; k++) {
            float d = start + (reach - start) * k / RINGS;
            al[k] = edgeFalloff(d / sigma) * strength;
            int idx = 0;
            for (int i = 0; i < 4; i++) {
                int prev = (i + 3) % 4;
                float a0 = ang[prev];
                float da = ang[i] - a0;
                while (da > Math.PI) da -= (float) (2 * Math.PI);
                while (da < -Math.PI) da += (float) (2 * Math.PI);
                for (int s = 0; s <= ARC; s++) {
                    float a = a0 + da * s / ARC;
                    px[k][idx] = poly[i * 2] + (float) Math.cos(a) * d;
                    py[k][idx] = poly[i * 2 + 1] + (float) Math.sin(a) * d;
                    idx++;
                }
            }
        }
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int k = 0; k < RINGS; k++) {
            for (int j = 0; j < n; j++) {
                int j2 = (j + 1) % n;
                glowVertex(b, m, px[k][j], py[k][j], al[k]);
                glowVertex(b, m, px[k + 1][j], py[k + 1][j], al[k + 1]);
                glowVertex(b, m, px[k + 1][j2], py[k + 1][j2], al[k + 1]);
                glowVertex(b, m, px[k][j2], py[k][j2], al[k]);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static java.lang.reflect.Method maskMethod;
    private static boolean maskFailed;

    /** the screen shape of the flashlight hand (from the flashlight mod, by reflection - the mods stay independent), written only into the depth buffer. */
    private static void maskHand(Matrix4f m, int gw, int gh) {
        if (maskFailed) return;
        try {
            if (maskMethod == null) {
                maskMethod = Class.forName("com.example.slenderflashlight.client.FlashlightClient").getMethod("handMask");
            }
            float[] q = (float[]) maskMethod.invoke(null);
            if (q == null || q.length < 8) return;
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(true);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            for (int i = 0; i + 7 < q.length; i += 8) {
                for (int k = 0; k < 4; k++) {
                    float x = (q[i + k * 2] * 0.5F + 0.5F) * gw;
                    float y = (1F - (q[i + k * 2 + 1] * 0.5F + 0.5F)) * gh;
                    b.vertex(m, x, y, 10F).color(0, 0, 0, 0).endVertex();
                }
            }
            BufferUploader.drawWithShader(b.end());
            RenderSystem.colorMask(true, true, true, true);
        } catch (ClassNotFoundException | NoSuchMethodException ex) {
            maskFailed = true;       // the flashlight mod is not there: nothing to hide behind
        } catch (Throwable ex) {
            RenderSystem.colorMask(true, true, true, true);
            maskFailed = true;
        }
    }

    private static void glowVertex(BufferBuilder b, Matrix4f m, float x, float y, float alpha) {
        b.vertex(m, x, y, 0F).color(COL_R, COL_G, COL_B, Mth.clamp(alpha, 0F, 1F)).endVertex();
    }
}
