package com.example.autoenchants;

import com.example.autoenchants.client.render.PeekabooSparkEntityRenderer;
import com.example.autoenchants.client.render.SquidMissileEntityRenderer;
import com.example.autoenchants.client.render.SuperGolemSnowballEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.ShulkerEntityRenderer;
import net.minecraft.client.render.entity.SnowGolemEntityRenderer;

public class AutoEnchantsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(AutoEnchantsMod.PEEKABOO_SHELL, ShulkerEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.PEEKABOO_SPARK, PeekabooSparkEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.SQUID_MISSILE, SquidMissileEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.SUPER_GOLEM_SNOWBALL, SuperGolemSnowballEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.SUPER_SNOW_GOLEM, SnowGolemEntityRenderer::new);
    }
}
