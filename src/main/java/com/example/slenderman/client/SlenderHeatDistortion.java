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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class SlenderHeatDistortion {
    // Параметры оригинального шейдера HeatDistortion
    private static final float STRENGTH = 0.2F;       // диапазон в оригинале: 0.1 .. 0.3
    private static final float TRANSPARENCY = 0.05F;  // диапазон в оригинале: 0.01 .. 0.1
    private static final float NOISE_TILING = 2.5F;   // плотность волн на меше

    // Размеры 3D-эллипсоида искажения вокруг Слендермена (в блоках)
    private static final float RADIUS_XZ = 2.4F;
    private static final float RADIUS_Y = 2.6F;
    private static final double MAX_DISTANCE = 70.0D;

    // Плотность сетки 3D-сферы (для плавного (N·V)^2 по краям)
    private static final int SLICES = 24;
    private static final int STACKS = 16;

    // Мерцание эффекта из скрипта игры (поставь 1.0F, если нужно чтобы работал непрерывно)
    private static final float FLICKER_CHANCE = 0.25F;
    private static final long FLICKER_INTERVAL_MS = 16L;

    private static final RandomSource RNG = RandomSource.create();
    private static ShaderInstance heatShader;
    private static RenderTarget grabBuffer;
    private static long nextFlickerMs;
    private static boolean flickerActive;

    private SlenderHeatDistortion() {}

    @Mod.EventBusSubscriber(modid = SlendermanMod.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ShaderRegistry {
        @SubscribeEvent
        public static void onRegisterShaders(RegisterShadersEvent event) {
            try {
                event.registerShader(
                        new ShaderInstance(
                                event.getResourceManager(),
                                new ResourceLocation(SlendermanMod.ID, "heat_distortion"),
                                DefaultVertexFormat.POSITION_COLOR_TEX
                        ),
                        instance -> heatShader = instance
                );
            } catch (Throwable t) {
                t.printStackTrace();
                heatShader = null;
            }
        }
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || heatShader == null) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        SlendermanEntity target = findClosestVisible(mc);
        if (target == null) {
            return;
        }

        updateFlicker();
        if (!flickerActive) {
            return;
        }

        try {
            renderDistortionMesh(mc, event, target);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            mc.getMainRenderTarget().bindWrite(false);
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
        }
    }

    private static SlendermanEntity findClosestVisible(Minecraft mc) {
        SlendermanEntity best = null;
        double bestDistSq = MAX_DISTANCE * MAX_DISTANCE;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (entity instanceof SlendermanEntity s) {
                if (s.isInvisible() || s.isTeleportHidden() || s.isWatcher() || !s.isAlive() || s.getDeathPhase() >= 2) {
                    continue;
                }
                double distSq = s.distanceToSqr(mc.player);
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = s;
                }
            }
        }
        return best;
    }

    private static void updateFlicker() {
        long now = System.currentTimeMillis();
        if (now >= nextFlickerMs) {
            nextFlickerMs = now + FLICKER_INTERVAL_MS;
            flickerActive = RNG.nextFloat() < FLICKER_CHANCE;
        }
    }

    private static void renderDistortionMesh(Minecraft mc, RenderLevelStageEvent event, SlendermanEntity target) {
        RenderTarget main = mc.getMainRenderTarget();
        int width = main.width;
        int height = main.height;
        if (width <= 0 || height <= 0) {
            return;
        }

        if (grabBuffer == null) {
            grabBuffer = new TextureTarget(width, height, false, Minecraft.ON_OSX);
            grabBuffer.setFilterMode(GL11.GL_LINEAR);
        } else if (grabBuffer.width != width || grabBuffer.height != height) {
            grabBuffer.resize(width, height, Minecraft.ON_OSX);
        }

        // Аналог GrabPass: копируем текущий кадр перед рисованием искажения
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, grabBuffer.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        main.bindWrite(false);

        Vec3 cam = event.getCamera().getPosition();
        Vec3 pos = target.getPosition(event.getPartialTick());
        float cx = (float) (pos.x - cam.x);
        float cy = (float) (pos.y + target.getBbHeight() * 0.5D - cam.y);
        float cz = (float) (pos.z - cam.z);

        float timeSeconds = (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000.0F;

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.enableCull();
        RenderSystem.colorMask(true, true, true, false);

        RenderSystem.setShader(() -> heatShader);
        RenderSystem.setShaderTexture(0, grabBuffer.getColorTextureId());
        heatShader.safeGetUniform("ScreenSize").set((float) width, (float) height);
        heatShader.safeGetUniform("DistortParams").set(STRENGTH, TRANSPARENCY, timeSeconds, NOISE_TILING);

        Matrix4f modelView = event.getPoseStack().last().pose();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);

        for (int j = 0; j < STACKS; j++) {
            float v0 = (float) j / STACKS;
            float v1 = (float) (j + 1) / STACKS;
            double phi0 = Math.PI * v0;
            double phi1 = Math.PI * v1;

            for (int i = 0; i < SLICES; i++) {
                float u0 = (float) i / SLICES;
                float u1 = (float) (i + 1) / SLICES;
                double th0 = 2.0D * Math.PI * u0;
                double th1 = 2.0D * Math.PI * u1;

                emitVertex(builder, modelView, cx, cy, cz, phi0, th0, u0, v0);
                emitVertex(builder, modelView, cx, cy, cz, phi0, th1, u1, v0);
                emitVertex(builder, modelView, cx, cy, cz, phi1, th1, u1, v1);
                emitVertex(builder, modelView, cx, cy, cz, phi1, th0, u0, v1);
            }
        }

        BufferUploader.drawWithShader(builder.end());
    }

    private static void emitVertex(BufferBuilder b, Matrix4f m, float cx, float cy, float cz,
                                   double phi, double theta, float u, float v) {
        float sinPhi = (float) Math.sin(phi);
        float cosPhi = (float) Math.cos(phi);
        float cosTh = (float) Math.cos(theta);
        float sinTh = (float) Math.sin(theta);

        float sx = sinPhi * cosTh;
        float sy = cosPhi;
        float sz = sinPhi * sinTh;

        float px = cx + sx * RADIUS_XZ;
        float py = cy + sy * RADIUS_Y;
        float pz = cz + sz * RADIUS_XZ;

        // Нормаль эллипсоида в пространстве камеры для корректного dot(N, V)^2
        float nx = sx / RADIUS_XZ;
        float ny = sy / RADIUS_Y;
        float nz = sz / RADIUS_XZ;
        float invLen = 1.0F / (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        Vector4f norm = new Vector4f(nx * invLen, ny * invLen, nz * invLen, 0.0F);
        norm.transform(m);

        b.vertex(m, px, py, pz)
                .color(
                        Mth.clamp(norm.x() * 0.5F + 0.5F, 0.0F, 1.0F),
                        Mth.clamp(norm.y() * 0.5F + 0.5F, 0.0F, 1.0F),
                        Mth.clamp(norm.z() * 0.5F + 0.5F, 0.0F, 1.0F),
                        1.0F
                )
                .uv(u, v)
                .endVertex();
    }
}