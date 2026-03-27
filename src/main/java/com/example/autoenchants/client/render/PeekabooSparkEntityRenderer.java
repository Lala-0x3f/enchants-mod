package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.PeekabooSparkEntity;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.EntityRenderState;

public class PeekabooSparkEntityRenderer extends EntityRenderer<PeekabooSparkEntity, EntityRenderState> {
    public PeekabooSparkEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    public EntityRenderState createRenderState() {
        return new EntityRenderState();
    }
}
