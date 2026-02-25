package com.example.autoenchants.client.render;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.entity.PeekabooSparkEntity;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public class PeekabooSparkEntityRenderer extends EntityRenderer<PeekabooSparkEntity> {
    private static final Identifier TEXTURE = AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_spark.png");
    private static final RenderLayer LAYER = RenderLayer.getEntityTranslucent(TEXTURE);
    private static final float HALF_SIZE = 0.3f;

    public PeekabooSparkEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    protected int getBlockLight(PeekabooSparkEntity entity, BlockPos pos) {
        return Math.max(super.getBlockLight(entity, pos), 10);
    }

    @Override
    public void render(PeekabooSparkEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();
        float age = entity.age + tickDelta;
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(MathHelper.sin(age * 0.3f) * 180.0f));
        matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(MathHelper.cos(age * 0.2f) * 180.0f));
        matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(MathHelper.sin(age * 0.35f) * 180.0f));

        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(LAYER);
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f posMatrix = entry.getPositionMatrix();
        Matrix3f normalMatrix = entry.getNormalMatrix();

        vertex(vertexConsumer, posMatrix, normalMatrix, light, -HALF_SIZE, -HALF_SIZE, 0, 1);
        vertex(vertexConsumer, posMatrix, normalMatrix, light, HALF_SIZE, -HALF_SIZE, 1, 1);
        vertex(vertexConsumer, posMatrix, normalMatrix, light, HALF_SIZE, HALF_SIZE, 1, 0);
        vertex(vertexConsumer, posMatrix, normalMatrix, light, -HALF_SIZE, HALF_SIZE, 0, 0);

        matrices.pop();
        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    private static void vertex(VertexConsumer vertexConsumer, Matrix4f posMatrix, Matrix3f normalMatrix, int light, float x, float y, float u, float v) {
        vertexConsumer.vertex(posMatrix, x, y, 0.0f)
                .color(255, 255, 255, 255)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(normalMatrix, 0.0f, 1.0f, 0.0f)
                .next();
    }

    @Override
    public Identifier getTexture(PeekabooSparkEntity entity) {
        return TEXTURE;
    }
}