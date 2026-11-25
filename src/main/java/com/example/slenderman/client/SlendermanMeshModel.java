package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.client.mesh.SlenderMesh;
import com.example.slenderman.client.mesh.SlenderPose;
import com.example.slenderman.client.mesh.SlenderTentacles;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.io.InputStream;

/**
 * The new Slenderman: the real 3D mesh (thousands of small faces), skinned to the same joints the classic model has.
 *
 * The animations are NOT copied: a classic SlendermanModel is kept inside (never drawn) and calculates every pose as before
 * (the walk, the reach, the bow, the head tilt, ...). The angles of its parts are then given to the joints of the mesh,
 * so everything that is changed in SlendermanModel.setupAnim changes this model too.
 * The tentacles of the mesh come out by the same number as the old ones (entity.getTentacles: from 20 % of the sanity lost).
 */
public class SlendermanMeshModel extends EntityModel<SlendermanEntity> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation MESH_FILE = new ResourceLocation("slenderman", "models/entity/slender_mesh.bin");
    private static final ResourceLocation TENT_FILE = new ResourceLocation("slenderman", "models/entity/slender_tentacles.bin");

    private static SlenderMesh mesh;
    private static SlenderTentacles tentacles;
    private static boolean tried = false;

    /** loads the model files once (the first time the new model is needed). false = they are missing or broken, the old model is used then. */
    public static synchronized boolean ensureLoaded() {
        if (!tried) {
            tried = true;
            try (InputStream in = Minecraft.getInstance().getResourceManager().getResource(MESH_FILE).get().open()) {
                mesh = SlenderMesh.read(in);
            } catch (Exception e) {
                LOGGER.error("Slenderman mesh model could not be loaded, the classic model is used", e);
                mesh = null;
            }
            try (InputStream in = Minecraft.getInstance().getResourceManager().getResource(TENT_FILE).get().open()) {
                tentacles = SlenderTentacles.read(in);
            } catch (Exception e) {
                LOGGER.error("Slenderman mesh tentacles could not be loaded (no tentacles on the new model)", e);
                tentacles = null;
            }
        }
        return mesh != null;
    }

    private final SlendermanModel classic;
    private final ModelPart[] parts = new ModelPart[SlenderMesh.RIG];
    private final float[] rot = new float[SlenderMesh.RIG * 3];

    private SlenderPose pose;
    private float[] bodyPos;
    private float[] bodyNrm;
    private boolean posed = false;

    public SlendermanMeshModel(ModelPart root) {
        super(RenderType::entityCutoutNoCull);
        this.classic = new SlendermanModel(root);
        ModelPart torso = root.getChild("torso");
        ModelPart leftArm = torso.getChild("left_arm");
        ModelPart leftForearm = leftArm.getChild("left_forearm");
        ModelPart rightArm = torso.getChild("right_arm");
        ModelPart rightForearm = rightArm.getChild("right_forearm");
        ModelPart leftThigh = root.getChild("left_thigh");
        ModelPart rightThigh = root.getChild("right_thigh");
        // the same order as SlenderMesh.RIG_NAMES
        parts[0] = torso;
        parts[1] = torso.getChild("head");
        parts[2] = leftArm;
        parts[3] = leftForearm;
        parts[4] = leftForearm.getChild("left_hand");
        parts[5] = rightArm;
        parts[6] = rightForearm;
        parts[7] = rightForearm.getChild("right_hand");
        parts[8] = leftThigh;
        parts[9] = leftThigh.getChild("left_shin");
        parts[10] = rightThigh;
        parts[11] = rightThigh.getChild("right_shin");
    }

    @Override
    public void setupAnim(SlendermanEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        posed = false;
        if (!ensureLoaded()) return;
        if (pose == null) {
            pose = new SlenderPose(mesh, tentacles);
            bodyPos = new float[mesh.vertCount * 3];
            bodyNrm = new float[mesh.vertCount * 3];
        }
        // the old model calculates all the poses of the walk / reach / bow ...
        classic.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        for (int i = 0; i < SlenderMesh.RIG; i++) {
            rot[i * 3] = parts[i].xRot;
            rot[i * 3 + 1] = parts[i].yRot;
            rot[i * 3 + 2] = parts[i].zRot;
        }
        pose.setJoints(rot);
        pose.skinBody(bodyPos, bodyNrm);
        float partial = ageInTicks - entity.tickCount;
        // the portrait of the death has no tentacles, he just stands there
        float tent = DeathSequenceClient.isPortrait() ? 0F : entity.getTentacles(partial);
        pose.poseTentacles(tent, ageInTicks);
        posed = true;
    }

    public boolean hasTentacles() {
        return posed && tentacles != null && pose.tentaclesVisible();
    }

    @Override
    public void renderToBuffer(PoseStack poseStack, VertexConsumer buffer, int light, int overlay,
                               float r, float g, float b, float a) {
        if (!posed) return;
        Matrix4f m = poseStack.last().pose();
        Matrix3f n = poseStack.last().normal();
        final float k = 1F / 16F;
        for (int t = 0; t < mesh.triCount; t++) {
            // a triangle is sent as a quad whose last two corners are the same
            for (int c = 0; c < 4; c++) {
                int v = mesh.idx[t * 3 + (c < 3 ? c : 2)] & 0xFFFF;
                buffer.vertex(m, bodyPos[v * 3] * k, bodyPos[v * 3 + 1] * k, bodyPos[v * 3 + 2] * k)
                        .color(r, g, b, a)
                        .uv(mesh.uv[v * 2], mesh.uv[v * 2 + 1])
                        .overlayCoords(overlay)
                        .uv2(light)
                        .normal(n, bodyNrm[v * 3], bodyNrm[v * 3 + 1], bodyNrm[v * 3 + 2])
                        .endVertex();
            }
        }
    }

    /** the tentacles use their own (black) texture, so they are drawn separately by the layer of the renderer. */
    public void renderTentacles(PoseStack poseStack, VertexConsumer buffer, int light, int overlay) {
        if (!hasTentacles()) return;
        Matrix4f m = poseStack.last().pose();
        Matrix3f n = poseStack.last().normal();
        final float k = 1F / 16F;
        for (int ti = 0; ti < tentacles.count; ti++) {
            float[] p = pose.tentaclePos(ti);
            float[] nr = pose.tentacleNormals(ti);
            short[] idx = tentacles.idx[ti];
            for (int t = 0; t < tentacles.triCount[ti]; t++) {
                for (int c = 0; c < 4; c++) {
                    int v = idx[t * 3 + (c < 3 ? c : 2)] & 0xFFFF;
                    buffer.vertex(m, p[v * 3] * k, p[v * 3 + 1] * k, p[v * 3 + 2] * k)
                            .color(1F, 1F, 1F, 1F)
                            .uv(0.5F, 0.5F)
                            .overlayCoords(overlay)
                            .uv2(light)
                            .normal(n, nr[v * 3], nr[v * 3 + 1], nr[v * 3 + 2])
                            .endVertex();
                }
            }
        }
    }
}
