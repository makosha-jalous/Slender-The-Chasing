package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.client.mesh.SlenderPose;
import com.example.slenderman.client.mesh.SlenderTentacles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The tentacles of the mesh model on the CLASSIC (cube) Slenderman, instead of his old cube tentacles.
 * The shape, the growing out of the back (from 20 % of the sanity lost: entity.getTentacles) and the slow writhing are exactly
 * the same code as on the mesh model (SlenderPose.poseTentacles); only the place is different: they are carried by the torso
 * of the classic model, so they follow its lean and turn.
 */
public class ClassicTentacleLayer extends RenderLayer<SlendermanEntity, SlendermanModel> {
    private static final ResourceLocation TENTACLE_TEXTURE =
            new ResourceLocation("slenderman", "textures/entity/slenderman_tentacle.png");

    // the torso of the classic model turns around this point (the same numbers as in SlendermanModel: PartPose.offset(0, -4, 0))
    private static final float TORSO_PIVOT_X = 0F, TORSO_PIVOT_Y = -4F, TORSO_PIVOT_Z = 0F;
    // the classic back is shallower than the mesh back, so the tentacles are moved this much (up = -y, forward = -z) to sit on it
    private static final float SHIFT_X = 0F, SHIFT_Y = -3F, SHIFT_Z = -6.5F;

    private SlenderPose pose;
    private SlenderTentacles poseFor;
    private final float[] torsoMatrix = new float[12];

    public ClassicTentacleLayer(RenderLayerParent<SlendermanEntity, SlendermanModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, SlendermanEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (SlendermanModel.OLD_TENTACLES) return;
        if (!SlendermanMeshModel.ensureLoaded()) return;
        SlenderTentacles data = SlendermanMeshModel.tentacleData();
        if (data == null || SlendermanMeshModel.meshData() == null) return;
        // the portrait of the death has no tentacles: he just stands there
        float tent = DeathSequenceClient.isPortrait() ? 0F : entity.getTentacles(partialTick);
        if (tent <= 0.02F) return;
        if (pose == null || poseFor != data) {
            pose = new SlenderPose(null, data);
            poseFor = data;
        }
        ModelPart torso = getParentModel().torsoPart();
        float[] mp = SlendermanMeshModel.meshData().pivot;   // pivot of the torso joint of the mesh = the point that is moved onto the classic torso
        SlenderPose.classicTorsoMatrix(TORSO_PIVOT_X, TORSO_PIVOT_Y, TORSO_PIVOT_Z,
                torso.xRot, torso.yRot, torso.zRot,
                mp[0] - SHIFT_X, mp[1] - SHIFT_Y, mp[2] - SHIFT_Z, torsoMatrix);
        pose.poseTentacles(tent, ageInTicks, torsoMatrix);
        if (!pose.tentaclesVisible()) return;

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TENTACLE_TEXTURE));
        Matrix4f m = poseStack.last().pose();
        Matrix3f n = poseStack.last().normal();
        final float k = 1F / 16F;
        for (int ti = 0; ti < data.count; ti++) {
            float[] p = pose.tentaclePos(ti);
            float[] nr = pose.tentacleNormals(ti);
            short[] idx = data.idx[ti];
            for (int t = 0; t < data.triCount[ti]; t++) {
                // a triangle is sent as a quad whose last two corners are the same
                for (int c = 0; c < 4; c++) {
                    int v = idx[t * 3 + (c < 3 ? c : 2)] & 0xFFFF;
                    consumer.vertex(m, p[v * 3] * k, p[v * 3 + 1] * k, p[v * 3 + 2] * k)
                            .color(1F, 1F, 1F, 1F)
                            .uv(0.5F, 0.5F)
                            .overlayCoords(OverlayTexture.NO_OVERLAY)
                            .uv2(packedLight)
                            .normal(n, nr[v * 3], nr[v * 3 + 1], nr[v * 3 + 2])
                            .endVertex();
                }
            }
        }
    }
}
