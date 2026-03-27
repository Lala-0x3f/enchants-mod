package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.PeekabooSparkEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.util.math.BlockPos;

public class PeekabooSparkEntityRenderer extends FlyingItemEntityRenderer<PeekabooSparkEntity> {
    public PeekabooSparkEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    protected int getBlockLight(PeekabooSparkEntity entity, BlockPos pos) {
        return 15;
    }
}
