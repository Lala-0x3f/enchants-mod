package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.StingerMissileEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.BeeEntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.BeeEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.passive.BeeEntity;

public class StingerMissileEntityRenderer extends BeeEntityRenderer {
    public StingerMissileEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
        this.addFeature(new GlintFeature(this));
    }

    private static class GlintFeature extends FeatureRenderer<BeeEntity, BeeEntityModel<BeeEntity>> {
        private GlintFeature(FeatureRendererContext<BeeEntity, BeeEntityModel<BeeEntity>> context) {
            super(context);
        }

        @Override
        public void render(MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light,
                           BeeEntity entity, float limbAngle, float limbDistance, float tickDelta,
                           float animationProgress, float headYaw, float headPitch) {
            if (!(entity instanceof StingerMissileEntity)) {
                return;
            }
            VertexConsumer glint = vertexConsumers.getBuffer(RenderLayer.getEntityGlint());
            this.getContextModel().render(matrices, glint, light, net.minecraft.client.render.OverlayTexture.DEFAULT_UV,
                    1.0f, 1.0f, 1.0f, 1.0f);
        }
    }
}
