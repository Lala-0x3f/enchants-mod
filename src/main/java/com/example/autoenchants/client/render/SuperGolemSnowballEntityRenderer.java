package com.example.autoenchants.client.render;

import com.example.autoenchants.entity.SuperGolemSnowballEntity;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.FlyingItemEntityRenderer;
import net.minecraft.util.math.BlockPos;

public class SuperGolemSnowballEntityRenderer extends FlyingItemEntityRenderer<SuperGolemSnowballEntity> {
    public SuperGolemSnowballEntityRenderer(EntityRendererFactory.Context context) {
        super(context);
    }

    @Override
    protected int getBlockLight(SuperGolemSnowballEntity entity, BlockPos pos) {
        return 15;
    }
}
