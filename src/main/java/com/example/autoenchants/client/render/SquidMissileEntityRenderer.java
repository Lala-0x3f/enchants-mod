package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.SquidMissileEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.SquidEntityModel;
import net.minecraft.client.render.entity.state.SquidEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

public class SquidMissileEntityRenderer extends LivingEntityRenderer<SquidMissileEntity, SquidEntityRenderState, SquidEntityModel> {
    private static final Identifier TEXTURE = Identifier.ofVanilla("textures/entity/squid/squid.png");

    public SquidMissileEntityRenderer(EntityRendererFactory.Context context) {
        super(context, new SquidEntityModel(context.getPart(EntityModelLayers.SQUID)), 0.4f);
    }

    @Override
    public SquidEntityRenderState createRenderState() {
        return new SquidEntityRenderState();
    }

    @Override
    public Identifier getTexture(SquidEntityRenderState state) {
        return TEXTURE;
    }

    @Override
    public void updateRenderState(SquidMissileEntity entity, SquidEntityRenderState state, float tickDelta) {
        super.updateRenderState(entity, state, tickDelta);
        state.tentacleAngle = 0.0f;
        state.tiltAngle = 0.0f;
        state.rollAngle = 0.0f;
    }

    @Override
    protected void setupTransforms(SquidEntityRenderState state, MatrixStack matrices, float bodyYaw, float baseHeight) {
        // Completely override default setupTransforms (which applies 180-bodyYaw Y rotation)
        // LivingEntityRenderer.render() applies scale(-1,-1,1) AFTER this method,
        // flipping model Y (down in model space) to world Y (up).
        // In model space: body/head is at -Y (top), tentacles at +Y (bottom).
        // We need rotations that, after the scale flip, point the body in the flight direction.
        Vec3d vel = new Vec3d(state.x, state.y, state.z);
        if (vel.lengthSquared() > 0.001d) {
            float flyYaw = (float) Math.toDegrees(Math.atan2(-vel.x, vel.z));
            float flyPitch = (float) Math.toDegrees(Math.atan2(vel.y, Math.sqrt(vel.x * vel.x + vel.z * vel.z)));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-flyYaw));
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(90.0f - flyPitch));
        } else {
            // Ground phase - tentacles up
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(180.0f));
        }
    }

    @Override
    protected void scale(SquidEntityRenderState state, MatrixStack matrices) {
        matrices.scale(0.7f, 0.7f, 0.7f);
    }

    @Override
    protected boolean isShaking(SquidEntityRenderState state) {
        return false;
    }
}
