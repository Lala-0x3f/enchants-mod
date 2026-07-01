package com.example.autoenchants;

import com.example.autoenchants.client.render.ArmorPiercingArrowEntityRenderer;
import com.example.autoenchants.client.render.PeekabooShellEntityRenderer;
import com.example.autoenchants.client.render.PeekabooSparkEntityRenderer;
import com.example.autoenchants.client.render.SquidMissileEntityRenderer;
import com.example.autoenchants.client.render.StingerMissileEntityRenderer;
import com.example.autoenchants.client.render.SuperGolemSnowballEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.item.CompassAnglePredicateProvider;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.client.render.entity.AllayEntityRenderer;
import net.minecraft.client.render.entity.BeeEntityRenderer;
import net.minecraft.client.render.entity.SnowGolemEntityRenderer;
import net.minecraft.client.render.entity.TntEntityRenderer;
import net.minecraft.util.Identifier;

public class AutoEnchantsClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(AutoEnchantsMod.PEEKABOO_SHELL, PeekabooShellEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.PEEKABOO_SPARK, PeekabooSparkEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.SQUID_MISSILE, SquidMissileEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.SUPER_GOLEM_SNOWBALL, SuperGolemSnowballEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.SUPER_SNOW_GOLEM, SnowGolemEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.BOMBER_ALLAY, AllayEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.BEE_MISSILE, BeeEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.STINGER_MISSILE, StingerMissileEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.BOMBER_TNT, TntEntityRenderer::new);
        EntityRendererRegistry.register(AutoEnchantsMod.ARMOR_PIERCING_ARROW, ArmorPiercingArrowEntityRenderer::new);

        // 为目标指针注册 angle 模型谓词提供者，使其能正确渲染指南针动画
        // 原版仅为 Items.LODESTONE_COMPASS 注册此属性，自定义 CompassItem 需手动注册
        ModelPredicateProviderRegistry.register(
                AutoEnchantsMod.TARGET_POINTER,
                new Identifier("angle"),
                new CompassAnglePredicateProvider(null)
        );
    }
}
