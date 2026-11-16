package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Slenderman model, "classic blocky" version (as in the reference screenshots).
 *
 * Units: 1 model unit = 1/16 block (normal Minecraft / Blockbench units), so NO extra scale in the renderer.
 * Total height = 64 units = 4 blocks:  legs 28 + torso 26 + square head 10.  There is no neck.
 * The ground is at y = +24 (vanilla convention), the top of the head is at y = -40.
 * Texture: 128 x 128 (1 texel = 1 unit).
 *
 * createBodyLayer() contains only plain numbers on purpose, so Blockbench can import this file
 * (File > Import > Import Java Entity) and export it back after editing.
 */
public class SlendermanModel extends EntityModel<SlendermanEntity> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(new ResourceLocation("slenderman", "slenderman"), "main");

    private static final float DEG = 0.017453292F;

    // ---- resting pose ----
    /** elbows are slightly bent when he just stands (radians, about 16 degrees). */
    private static final float REST_ELBOW = -0.28F;

    // ---- reach (sharp screamer pose, 1 s) ----
    /** how far the torso bends forward while he reaches (radians, about 17 degrees). */
    private static final float REACH_LEAN = 0.30F;
    /** absolute angle of the right arm: 0 = hanging, -PI/2 = horizontal forward. */
    private static final float REACH_ARM = -1.5707964F;
    /** the elbow stays slightly bent while reaching. */
    private static final float REACH_ELBOW = -0.20F;
    /** the arm turns a bit inwards (towards the player in front of him). */
    private static final float REACH_AIM_IN = -0.15F;
    /** head tilts sideways towards the reaching (right) arm; negative = right side goes down. */
    private static final float REACH_HEAD_TILT = -0.30F;

    // ---- the walk (the amplitudes are taken from the reference video: slow, the arms hang and swing a little, the knee lifts, the torso is hunched) ----
    /** torso + head lean forward (radians, ~12 degrees). */
    private static final float WALK_LEAN = 0.21F;
    /** the torso turns this far to each side (radians, ~16 degrees). */
    private static final float WALK_TORSO_YAW = 0.10F;
    /** the arms swing this far (radians, ~34 degrees). */
    private static final float WALK_ARM = 0.22F;
    /** the extra bend of the forearm at the top of the swing (radians). */
    private static final float WALK_ELBOW = 0.18F;
    /** the thigh goes this far forward / back (radians, ~26 degrees) and the knee bends this much at most (radians, ~52 degrees). */
    private static final float WALK_STEP = 0.30F;
    private static final float WALK_KNEE = 0.75F;

    /** how far FORWARD the thigh is (+ forward) at this phase of the leg: the first half = the step (back to forward), the second = straight, going back. */
    private static float legSwing(float phase) {
        float h = phase % (2F * Mth.PI);
        if (h < 0F) h += 2F * Mth.PI;
        if (h < Mth.PI) {
            float u = h / Mth.PI;
            u = u * u * (3F - 2F * u);
            return Mth.lerp(u, -WALK_STEP, WALK_STEP);
        }
        float u = (h - Mth.PI) / Mth.PI;
        return Mth.lerp(u, WALK_STEP, -WALK_STEP);
    }

    /** the bend of the knee: only during the step, straight at both ends of it. */
    private static float legKnee(float phase) {
        float h = phase % (2F * Mth.PI);
        if (h < 0F) h += 2F * Mth.PI;
        return h < Mth.PI ? WALK_KNEE * Mth.sin(h) : 0F;
    }

    /** the head tilt of the waiting mode (radians, about 12 degrees, to his right side). */
    private static final float IDLE_HEAD_TILT = 0.22F;

    // ---- bow (slow, 3 s) ----
    private static final float BOW_LEAN = 0.40F;
    private static final float BOW_HEAD_TILT = 0.28F;

    // ---- tentacles: 6 pieces, every one is a chain of 8 short tapering segments, so it bends smoothly like a snake ----
    private static final int TENTACLES = 6;
    private static final int SEGMENTS = 8;
    /** how far each pair of tentacles spreads outwards (rad). Upper pair rises, lower pair is almost level. */
    private static final float[] TENT_SPREAD = {2.45F, 2.05F, 1.70F};

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart leftThigh;
    private final ModelPart rightThigh;
    private final ModelPart leftShin;
    private final ModelPart rightShin;
    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart leftForearm;
    private final ModelPart leftHand;
    private final ModelPart rightArm;
    private final ModelPart rightForearm;
    private final ModelPart rightHand;
    private final ModelPart[][] tentacles = new ModelPart[TENTACLES][SEGMENTS];

    public SlendermanModel(ModelPart root) {
        this.root = root;
        this.leftThigh = root.getChild("left_thigh");
        this.rightThigh = root.getChild("right_thigh");
        this.leftShin = leftThigh.getChild("left_shin");
        this.rightShin = rightThigh.getChild("right_shin");
        this.torso = root.getChild("torso");
        this.head = torso.getChild("head");
        this.leftArm = torso.getChild("left_arm");
        this.leftForearm = leftArm.getChild("left_forearm");
        this.leftHand = leftForearm.getChild("left_hand");
        this.rightArm = torso.getChild("right_arm");
        this.rightForearm = rightArm.getChild("right_forearm");
        this.rightHand = rightForearm.getChild("right_hand");
        for (int i = 0; i < TENTACLES; i++) {
            ModelPart seg = torso.getChild("t" + i);
            tentacles[i][0] = seg;
            for (int j = 1; j < SEGMENTS; j++) {
                seg = seg.getChild("s" + j);
                tentacles[i][j] = seg;
            }
        }
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // ---- torso ----
        PartDefinition torso = root.addOrReplaceChild("torso",
            CubeListBuilder.create()
            .texOffs(0, 20).addBox(-6.0F, -6.8866F, -3.0F, 12.0F, 6.9732F, 6.0F, new CubeDeformation(0.0F, 0.0134F, -0.2F))
            .texOffs(0, 11).addBox(-6.0F, -15.8828F, -3.1F, 12.0F, 8.9655F, 6.0F, new CubeDeformation(-0.4F, 0.0172F, -0.1F))
            .texOffs(0, 1).addBox(-6.0F, -25.9807F, -3.15F, 12.0F, 10.0613F, 6.0F, new CubeDeformation(0.3F, 0.0194F, 0.15F)),
            PartPose.offset(0.0F, -4.0F, 0.0F));
        // ---- head ----
        PartDefinition head = torso.addOrReplaceChild("head",
            CubeListBuilder.create()
            .texOffs(40, 0).addBox(-5.0F, -9.3F, -5.7F, 10.0F, 10.0F, 10.0F, new CubeDeformation(-0.7F, -0.7F, -0.6F)),
            PartPose.offset(0.0F, -26.0F, 0.0F));
        // ---- left_arm ----
        PartDefinition left_arm = torso.addOrReplaceChild("left_arm",
            CubeListBuilder.create(),
            PartPose.offset(9.0F, -24.0F, 0.0F));
        left_arm.addOrReplaceChild("left_arm_part1",
            CubeListBuilder.create()
                .texOffs(0, 51).addBox(-1.8F, 8.5F, -2.0F, 4.0F, 9.0F, 4.0F, new CubeDeformation(0.7F, 0.0F, 0.8F)),
            PartPose.offsetAndRotation(0.0F, -0.7F, 0.0F, 0.0815F * DEG, -0.1422F * DEG, -2.4947F * DEG));
        left_arm.addOrReplaceChild("left_arm_part2",
            CubeListBuilder.create()
                .texOffs(0, 40).addBox(-1.8F, -2.0F, -2.0F, 4.0F, 11.0F, 4.0F, new CubeDeformation(0.7F, -0.5F, 0.8F)),
            PartPose.offsetAndRotation(-0.3F, -0.7F, 0.0F, 0.0876F * DEG, -0.1385F * DEG, -4.9947F * DEG));
        // ---- left_forearm ----
        PartDefinition left_forearm = left_arm.addOrReplaceChild("left_forearm",
            CubeListBuilder.create(),
            PartPose.offset(0.0F, 18.0F, 0.0F));
        left_forearm.addOrReplaceChild("left_forearm_part1",
            CubeListBuilder.create()
                .texOffs(16, 40).addBox(-1.8F, 0.5F, -2.0F, 4.0F, 20.0F, 4.0F, new CubeDeformation(0.65F, -3.0F, 0.75F)),
            PartPose.offsetAndRotation(0.9F, -4.7F, 0.0F, 0.0F * DEG, 0.0F * DEG, 2.5F * DEG));
        // ---- left_hand ----
        PartDefinition left_hand = left_forearm.addOrReplaceChild("left_hand",
            CubeListBuilder.create(),
            PartPose.offset(0.0F, 20.0F, 0.0F));
        left_hand.addOrReplaceChild("left_hand_part1",
            CubeListBuilder.create()
                .texOffs(34, 40).addBox(0.8F, 0.5F, -2.5F, 1.2F, 4.0F, 5.0F),
            PartPose.offsetAndRotation(0.2F, -7.8F, 0.0F, 0.0F * DEG, -8.0F * DEG, 5.0F * DEG));
        left_hand.addOrReplaceChild("left_hand_part2",
            CubeListBuilder.create()
                .texOffs(35, 41).addBox(-1.3F, 0.5F, -2.5F, 3.3F, 0.7F, 4.0F),
            PartPose.offsetAndRotation(-0.5F, -7.8F, 0.4F, 0.0F * DEG, -8.0F * DEG, 5.0F * DEG));
        left_hand.addOrReplaceChild("left_hand_part3",
            CubeListBuilder.create()
                .texOffs(34, 40).addBox(0.8F, 1.5F, -2.5F, 1.2F, 3.0F, 5.0F),
            PartPose.offsetAndRotation(0.5F, -5.3F, 0.0F, 0.0F * DEG, -9.0F * DEG, 23.0F * DEG));
        left_hand.addOrReplaceChild("left_hand_part4",
            CubeListBuilder.create()
                .texOffs(37, 43).addBox(0.0F, 0.5F, -2.5F, 2.0F, 2.0F, 2.0F),
            PartPose.offsetAndRotation(-1.3F, -7.8F, 0.0F, 0.0F * DEG, 0.0F * DEG, 5.0F * DEG));
        left_hand.addOrReplaceChild("left_hand_part5",
            CubeListBuilder.create()
                .texOffs(39, 44).addBox(0.0F, 0.5F, -2.5F, 1.5F, 2.0F, 1.4F),
            PartPose.offsetAndRotation(-1.3F, -5.8F, 0.0F, 0.0F * DEG, 0.0F * DEG, 5.0F * DEG));
        // ---- right_arm ----
        PartDefinition right_arm = torso.addOrReplaceChild("right_arm",
            CubeListBuilder.create(),
            PartPose.offset(-9.0F, -24.0F, 0.0F));
        right_arm.addOrReplaceChild("right_arm_part1",
            CubeListBuilder.create()
                .texOffs(0, 51).addBox(-2.2F, 8.5F, -2.0F, 4.0F, 9.0F, 4.0F, new CubeDeformation(0.7F, 0.0F, 0.8F)),
            PartPose.offsetAndRotation(-0.1F, -0.7F, 0.0F, -0.0752F * DEG, 0.1456F * DEG, 2.4945F * DEG));
        right_arm.addOrReplaceChild("right_arm_part2",
            CubeListBuilder.create()
                .texOffs(0, 40).addBox(-2.2F, -2.0F, -2.0F, 4.0F, 11.0F, 4.0F, new CubeDeformation(0.7F, -0.5F, 0.8F)),
            PartPose.offsetAndRotation(0.3F, -0.7F, 0.0F, -0.0688F * DEG, 0.1487F * DEG, 4.9945F * DEG));
        // ---- right_forearm ----
        PartDefinition right_forearm = right_arm.addOrReplaceChild("right_forearm",
            CubeListBuilder.create(),
            PartPose.offset(0.0F, 18.0F, 0.0F));
        right_forearm.addOrReplaceChild("right_forearm_part1",
            CubeListBuilder.create()
                .texOffs(16, 40).addBox(-2.2F, 0.5F, -2.0F, 4.0F, 20.0F, 4.0F, new CubeDeformation(0.65F, -3.0F, 0.75F)),
            PartPose.offsetAndRotation(-0.9F, -4.7F, 0.0F, 0.0F * DEG, 0.0F * DEG, -2.5F * DEG));
        // ---- right_hand ----
        PartDefinition right_hand = right_forearm.addOrReplaceChild("right_hand",
            CubeListBuilder.create(),
            PartPose.offset(0.0F, 20.0F, 0.0F));
        right_hand.addOrReplaceChild("right_hand_part1",
            CubeListBuilder.create()
                .mirror().texOffs(34, 40).addBox(-2.0F, 0.5F, -2.5F, 1.2F, 4.0F, 5.0F).mirror(false),
            PartPose.offsetAndRotation(-0.2F, -7.8F, 0.0F, 0.0F * DEG, 8.0F * DEG, -5.0F * DEG));
        right_hand.addOrReplaceChild("right_hand_part2",
            CubeListBuilder.create()
                .mirror().texOffs(37, 43).addBox(-2.0F, 0.5F, -2.5F, 2.0F, 2.0F, 2.0F).mirror(false),
            PartPose.offsetAndRotation(1.3F, -7.8F, 0.0F, 0.0F * DEG, 0.0F * DEG, -5.0F * DEG));
        right_hand.addOrReplaceChild("right_hand_part3",
            CubeListBuilder.create()
                .mirror().texOffs(34, 40).addBox(-2.0F, 1.5F, -2.5F, 1.2F, 3.0F, 5.0F).mirror(false),
            PartPose.offsetAndRotation(-0.5F, -5.3F, 0.0F, 0.0F * DEG, 9.0F * DEG, -23.0F * DEG));
        right_hand.addOrReplaceChild("right_hand_part4",
            CubeListBuilder.create()
                .mirror().texOffs(39, 44).addBox(-1.5F, 0.5F, -2.5F, 1.5F, 2.0F, 1.4F).mirror(false),
            PartPose.offsetAndRotation(1.3F, -5.8F, 0.0F, 0.0F * DEG, 0.0F * DEG, -5.0F * DEG));
        right_hand.addOrReplaceChild("right_hand_part5",
            CubeListBuilder.create()
                .mirror().texOffs(35, 41).addBox(-2.0F, 0.5F, -2.5F, 3.3F, 0.7F, 4.0F).mirror(false),
            PartPose.offsetAndRotation(0.5F, -7.8F, 0.4F, 0.0F * DEG, 8.0F * DEG, -5.0F * DEG));
        // ---- t0 ----
        PartDefinition t0 = torso.addOrReplaceChild("t0",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(1.5F, -20.0F, 0.5F));
        // ---- s1 ----
        PartDefinition t0_s1 = t0.addOrReplaceChild("s1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s2 ----
        PartDefinition t0_s1_s2 = t0_s1.addOrReplaceChild("s2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s3 ----
        PartDefinition t0_s1_s2_s3 = t0_s1_s2.addOrReplaceChild("s3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s4 ----
        PartDefinition t0_s1_s2_s3_s4 = t0_s1_s2_s3.addOrReplaceChild("s4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s5 ----
        PartDefinition t0_s1_s2_s3_s4_s5 = t0_s1_s2_s3_s4.addOrReplaceChild("s5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s6 ----
        PartDefinition t0_s1_s2_s3_s4_s5_s6 = t0_s1_s2_s3_s4_s5.addOrReplaceChild("s6",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s7 ----
        PartDefinition t0_s1_s2_s3_s4_s5_s6_s7 = t0_s1_s2_s3_s4_s5_s6.addOrReplaceChild("s7",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- t1 ----
        PartDefinition t1 = torso.addOrReplaceChild("t1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(-1.5F, -20.0F, 0.5F));
        // ---- s1 ----
        PartDefinition t1_s1 = t1.addOrReplaceChild("s1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s2 ----
        PartDefinition t1_s1_s2 = t1_s1.addOrReplaceChild("s2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s3 ----
        PartDefinition t1_s1_s2_s3 = t1_s1_s2.addOrReplaceChild("s3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s4 ----
        PartDefinition t1_s1_s2_s3_s4 = t1_s1_s2_s3.addOrReplaceChild("s4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s5 ----
        PartDefinition t1_s1_s2_s3_s4_s5 = t1_s1_s2_s3_s4.addOrReplaceChild("s5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s6 ----
        PartDefinition t1_s1_s2_s3_s4_s5_s6 = t1_s1_s2_s3_s4_s5.addOrReplaceChild("s6",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s7 ----
        PartDefinition t1_s1_s2_s3_s4_s5_s6_s7 = t1_s1_s2_s3_s4_s5_s6.addOrReplaceChild("s7",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- t2 ----
        PartDefinition t2 = torso.addOrReplaceChild("t2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(1.5F, -13.0F, 0.5F));
        // ---- s1 ----
        PartDefinition t2_s1 = t2.addOrReplaceChild("s1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s2 ----
        PartDefinition t2_s1_s2 = t2_s1.addOrReplaceChild("s2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s3 ----
        PartDefinition t2_s1_s2_s3 = t2_s1_s2.addOrReplaceChild("s3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s4 ----
        PartDefinition t2_s1_s2_s3_s4 = t2_s1_s2_s3.addOrReplaceChild("s4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s5 ----
        PartDefinition t2_s1_s2_s3_s4_s5 = t2_s1_s2_s3_s4.addOrReplaceChild("s5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s6 ----
        PartDefinition t2_s1_s2_s3_s4_s5_s6 = t2_s1_s2_s3_s4_s5.addOrReplaceChild("s6",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s7 ----
        PartDefinition t2_s1_s2_s3_s4_s5_s6_s7 = t2_s1_s2_s3_s4_s5_s6.addOrReplaceChild("s7",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- t3 ----
        PartDefinition t3 = torso.addOrReplaceChild("t3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(-1.5F, -13.0F, 0.5F));
        // ---- s1 ----
        PartDefinition t3_s1 = t3.addOrReplaceChild("s1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s2 ----
        PartDefinition t3_s1_s2 = t3_s1.addOrReplaceChild("s2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s3 ----
        PartDefinition t3_s1_s2_s3 = t3_s1_s2.addOrReplaceChild("s3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s4 ----
        PartDefinition t3_s1_s2_s3_s4 = t3_s1_s2_s3.addOrReplaceChild("s4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s5 ----
        PartDefinition t3_s1_s2_s3_s4_s5 = t3_s1_s2_s3_s4.addOrReplaceChild("s5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s6 ----
        PartDefinition t3_s1_s2_s3_s4_s5_s6 = t3_s1_s2_s3_s4_s5.addOrReplaceChild("s6",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s7 ----
        PartDefinition t3_s1_s2_s3_s4_s5_s6_s7 = t3_s1_s2_s3_s4_s5_s6.addOrReplaceChild("s7",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- t4 ----
        PartDefinition t4 = torso.addOrReplaceChild("t4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(1.5F, -6.0F, 0.5F));
        // ---- s1 ----
        PartDefinition t4_s1 = t4.addOrReplaceChild("s1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s2 ----
        PartDefinition t4_s1_s2 = t4_s1.addOrReplaceChild("s2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s3 ----
        PartDefinition t4_s1_s2_s3 = t4_s1_s2.addOrReplaceChild("s3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s4 ----
        PartDefinition t4_s1_s2_s3_s4 = t4_s1_s2_s3.addOrReplaceChild("s4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s5 ----
        PartDefinition t4_s1_s2_s3_s4_s5 = t4_s1_s2_s3_s4.addOrReplaceChild("s5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s6 ----
        PartDefinition t4_s1_s2_s3_s4_s5_s6 = t4_s1_s2_s3_s4_s5.addOrReplaceChild("s6",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s7 ----
        PartDefinition t4_s1_s2_s3_s4_s5_s6_s7 = t4_s1_s2_s3_s4_s5_s6.addOrReplaceChild("s7",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- t5 ----
        PartDefinition t5 = torso.addOrReplaceChild("t5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(-1.5F, -6.0F, 0.5F));
        // ---- s1 ----
        PartDefinition t5_s1 = t5.addOrReplaceChild("s1",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s2 ----
        PartDefinition t5_s1_s2 = t5_s1.addOrReplaceChild("s2",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s3 ----
        PartDefinition t5_s1_s2_s3 = t5_s1_s2.addOrReplaceChild("s3",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s4 ----
        PartDefinition t5_s1_s2_s3_s4 = t5_s1_s2_s3.addOrReplaceChild("s4",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s5 ----
        PartDefinition t5_s1_s2_s3_s4_s5 = t5_s1_s2_s3_s4.addOrReplaceChild("s5",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s6 ----
        PartDefinition t5_s1_s2_s3_s4_s5_s6 = t5_s1_s2_s3_s4_s5.addOrReplaceChild("s6",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- s7 ----
        PartDefinition t5_s1_s2_s3_s4_s5_s6_s7 = t5_s1_s2_s3_s4_s5_s6.addOrReplaceChild("s7",
            CubeListBuilder.create()
            .texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        // ---- left_thigh ----
        PartDefinition left_thigh = root.addOrReplaceChild("left_thigh",
            CubeListBuilder.create()
            .texOffs(0, 70).addBox(-2.5F, 0.0F, -2.5F, 5.5F, 14.0F, 5.0F),
            PartPose.offset(3.0F, -4.0F, 0.0F));
        // ---- left_shin ----
        PartDefinition left_shin = left_thigh.addOrReplaceChild("left_shin",
            CubeListBuilder.create()
            .texOffs(0, 70).addBox(-2.5F, 0.0F, -2.5F, 5.5F, 14.0F, 5.0F)
            .texOffs(0, 69).addBox(-2.1F, 11.0F, -7.5F, 4.5F, 3.0F, 6.0F),
            PartPose.offset(0.0F, 14.0F, 0.0F));
        // ---- right_thigh ----
        PartDefinition right_thigh = root.addOrReplaceChild("right_thigh",
            CubeListBuilder.create()
            .texOffs(0, 70).addBox(-3.0F, 0.0F, -2.5F, 5.5F, 14.0F, 5.0F),
            PartPose.offset(-3.0F, -4.0F, 0.0F));
        // ---- right_shin ----
        PartDefinition right_shin = right_thigh.addOrReplaceChild("right_shin",
            CubeListBuilder.create()
            .texOffs(0, 70).addBox(-3.0F, 0.0F, -2.5F, 5.5F, 14.0F, 5.0F)
            .texOffs(0, 69).addBox(-2.5F, 11.0F, -7.5F, 4.5F, 3.0F, 6.0F),
            PartPose.offset(0.0F, 14.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void setupAnim(SlendermanEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float partial = ageInTicks - entity.tickCount;
        int action = entity.getAction();
        // 0..1: the sharp "arm over the player's head" screamer pose
        float reach = action == SlendermanEntity.ACTION_LUNGE ? entity.getProgress(partial) : 0F;
        // 0..1: slow bow with a sideways head tilt
        float bow = action == SlendermanEntity.ACTION_PEEK ? entity.getProgress(partial) : 0F;
        float side = entity.getActionSide();
        // the portrait of the death has no tentacles: he just stands there
        float tent = DeathSequenceClient.isPortrait() ? 0F : entity.getTentacles(partial);
        float lookPitch = Mth.clamp(headPitch, -40F, 40F) * DEG;

        // ---------- the walk (only while he really walks; fades in and out in 300 ms, zero when he stands or acts) ----------
        // One full cycle = 2.6 s (1.3 s per step, small swings like the reference video): the torso turns to his right and the RIGHT arm comes forward while the LEFT leg steps,
        // 500 ms the torso turns to his left and the LEFT arm comes forward while the RIGHT leg steps. The head does not move with the torso.
        float walk = entity.getWalkAnim(partial) * (1F - Math.max(reach, bow));
        float ph = ageInTicks * (Mth.PI / 26F);                   // 52 ticks = 2.6 s = one full cycle (as in the reference video: slow, small swings)
        float sRaw = Mth.sin(ph);
        float s = sRaw * walk;                                    // + = torso right and right arm forward, - = torso left and left arm forward
        float lean = WALK_LEAN * walk;                            // the hunch: torso and head a little forward

        // ---------- torso: dead still when standing, bends forward for reach / bow, hunched and turning while he walks ----------
        torso.xRot = reach * REACH_LEAN + bow * BOW_LEAN + lean;
        torso.yRot = s * WALK_TORSO_YAW;
        torso.zRot = 0F;

        // ---------- head: the turning of the torso is cancelled (it keeps looking where he looked); the hunch goes with the torso ----------
        head.yRot = Mth.clamp(netHeadYaw, -75F, 75F) * DEG - torso.yRot;
        head.xRot = lookPitch - (torso.xRot - lean);
        // the waiting tilt and the tilt of the reach are two separate animations (both to his right); the head follows the bigger one
        float idleTilt = entity.getHeadTilt(partial) * IDLE_HEAD_TILT;
        float tiltRad = Math.max(idleTilt, reach * -REACH_HEAD_TILT);
        head.zRot = -tiltRad + bow * side * BOW_HEAD_TILT;

        // ---------- legs: a step = the knee bends and the leg goes forward (500 ms), then it is straight and goes back (500 ms) ----------
        float swingL = legSwing(ph) * walk, swingR = legSwing(ph + Mth.PI) * walk;
        leftThigh.xRot = -swingL;
        rightThigh.xRot = -swingR;
        leftShin.xRot = legKnee(ph) * walk;
        rightShin.xRot = legKnee(ph + Mth.PI) * walk;

        // ---------- arms: they hang down (against the hunch), the one on the side of the turn comes forward and goes back ----------
        // (they sit on the torso, so they turn together with it around the vertical axis)
        leftArm.xRot = s * WALK_ARM - lean * 0.9F;
        rightArm.xRot = -s * WALK_ARM - lean * 0.9F;
        leftArm.yRot = 0F;
        rightArm.yRot = 0F;
        leftArm.zRot = -0.03F;
        rightArm.zRot = 0.03F;
        // the forearm bends a little forward at the top point of the arm
        float topR = Math.max(0F, sRaw), topL = Math.max(0F, -sRaw);
        leftForearm.xRot = REST_ELBOW - topL * topL * WALK_ELBOW * walk;
        rightForearm.xRot = REST_ELBOW - topR * topR * WALK_ELBOW * walk;
        leftHand.xRot = 0F;
        rightHand.xRot = 0F;

        if (bow > 0F) {
            // arms keep hanging straight down while the torso bows
            leftArm.xRot -= torso.xRot * 0.9F;
            rightArm.xRot -= torso.xRot * 0.9F;
        }

        if (reach > 0F) {
            // The right arm shoots forward and a little down, so the hand ends up above the PLAYER's head
            // (about half the angle he would need to point at the player's eyes). The torso already leans,
            // so the lean is subtracted to get the absolute angle.
            float down = Math.max(lookPitch * 0.5F, 0.05F);
            float target = REACH_ARM + down - REACH_LEAN;
            rightArm.xRot = Mth.lerp(reach, rightArm.xRot, target);
            rightArm.yRot = reach * REACH_AIM_IN;
            rightArm.zRot = Mth.lerp(reach, rightArm.zRot, 0F);
            rightForearm.xRot = Mth.lerp(reach, rightForearm.xRot, REACH_ELBOW);
            leftArm.xRot -= torso.xRot * 0.9F;
        }

        // ---------- tentacles: they push out of the CENTRE of his back, fan out, then writhe slowly ----------
        float grow = Mth.clamp(tent, 0F, 1F);
        float e = grow * grow * (3F - 2F * grow);
        float emerge = Mth.clamp(e / 0.35F, 0F, 1F);           // phase 1: swing out of the torso, backwards
        float fan = Mth.clamp((e - 0.35F) / 0.65F, 0F, 1F);    // phase 2: fan out sideways and upwards
        for (int i = 0; i < TENTACLES; i++) {
            float tside = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            float seed = i * 1.93F;
            ModelPart base = tentacles[i][0];
            base.visible = tent > 0.02F;

            base.xRot = Mth.lerp(fan, 1.5707964F * emerge, 0.55F) + 0.05F * e * Mth.sin(ageInTicks * 0.040F + seed * 1.3F);
            base.zRot = -tside * (TENT_SPREAD[level] * fan) + 0.06F * e * Mth.sin(ageInTicks * 0.045F + seed);
            base.yRot = tside * 0.08F * e * Mth.sin(ageInTicks * 0.035F + seed * 0.7F);

            for (int j = 1; j < SEGMENTS; j++) {
                ModelPart seg = tentacles[i][j];
                // the chain comes out segment by segment (a hidden segment hides everything after it)
                seg.visible = e > 0.25F + 0.65F * (j - 1) / (SEGMENTS - 1);
                // slow travelling waves in two directions with a phase shift = a soft, snake-like writhing;
                // every segment follows the one before it a little later, the tip moves the most
                float tentAmp = (0.65F + 0.08F * j) * e;
                float wave = Mth.sin(ageInTicks * 0.065F - j * 0.70F + seed);
                float sway = Mth.sin(ageInTicks * 0.050F - j * 0.60F + seed * 1.7F + 1.3F);
                seg.zRot = tside * 0.10F * fan + 0.20F * tentAmp * wave;   // gentle outward arc + side wave
                seg.xRot = 0.16F * tentAmp * sway;                          // up/down wave
                seg.yRot = 0F;
            }
        }
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                               float r, float g, float b, float a) {
        root.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
