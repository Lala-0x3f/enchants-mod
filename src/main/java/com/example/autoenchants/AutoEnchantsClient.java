package com.example.autoenchants;

import com.example.autoenchants.client.render.PeekabooShellEntityRenderer;
import com.example.autoenchants.client.render.PeekabooSparkEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class AutoEnchantsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(AutoEnchantsMod.PEEKABOO_SHELL, PeekabooShellEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.PEEKABOO_SPARK, PeekabooSparkEntityRenderer::new);
    }
}
