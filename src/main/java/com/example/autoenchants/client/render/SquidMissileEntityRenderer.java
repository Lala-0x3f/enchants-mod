package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.SquidMissileEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SquidEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;

public class SquidMissileEntityRenderer extends LivingEntityRenderer<SquidMissileEntity, SquidEntityModel<SquidMissileEntity>> {
    private static final Identifier TEXTURE = new Identifier("minecraft", "textures/entity/squid/squid.png");

    public SquidMissileEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new SquidEntityModel<>(context.getPart(EntityModelLayers.SQUID)), 0.4f);
    }

    @Override
    public Identifier getTexture(SquidMissileEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(SquidMissileEntity entity, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();

        // Rotate squid model to face flight direction
        net.minecraft.util.math.Vec3d vel = entity.getVelocity();
        if (vel.lengthSquared() > 0.001d) {
            float flyYaw = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
            float flyPitch = (float) Math.toDegrees(Math.atan2(vel.y, Math.sqrt(vel.x * vel.x + vel.z * vel.z)));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(flyYaw));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(flyPitch + 90.0f));
        } else {
            // Ground phase - point upward
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180.0f));
        }

        // Scale down slightly
        matrices.scale(0.7f, 0.7f, 0.7f);

        matrices.pop();

        super.render(entity, yaw, tickDelta, matrices, vertexConsumers, light);
    }

    @Override
    protected boolean isShaking(SquidMissileEntity entity) {
        return false;
    }
}