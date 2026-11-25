package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The renderer of the new mesh model. SlendermanRenderer hands the drawing over to it while /slenderskin is "mesh".
 * The model is in the same units as the classic one (1/16 block), so there is no extra scaling here either.
 */
public class SlendermanMeshRenderer extends MobRenderer<SlendermanEntity, SlendermanMeshModel> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("slenderman", "textures/entity/slenderman_mesh.png");
    private static final ResourceLocation TENTACLE_TEXTURE =
            new ResourceLocation("slenderman", "textures/entity/slenderman_tentacle.png");

    public SlendermanMeshRenderer(EntityRendererProvider.Context context) {
        super(context, new SlendermanMeshModel(context.bakeLayer(SlendermanModel.LAYER)), 0.6F);
        this.addLayer(new TentacleLayer(this));
    }

    @Override
    public ResourceLocation getTextureLocation(SlendermanEntity entity) {
        return TEXTURE;
    }

    /** the tentacles: drawn after the body with their own texture (inside the same pose of the entity). */
    private static class TentacleLayer extends RenderLayer<SlendermanEntity, SlendermanMeshModel> {
        TentacleLayer(RenderLayerParent<SlendermanEntity, SlendermanMeshModel> parent) {
            super(parent);
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, SlendermanEntity entity,
                           float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                           float netHeadYaw, float headPitch) {
            SlendermanMeshModel model = this.getParentModel();
            if (!model.hasTentacles()) return;
            VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(TENTACLE_TEXTURE));
            model.renderTentacles(poseStack, consumer, packedLight, OverlayTexture.NO_OVERLAY);
        }
    }
}
