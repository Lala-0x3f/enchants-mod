package com.example.autoenchants.mixin.client;

import net.minecraft.client.render.entity.ShulkerBulletEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Kept as a placeholder for potential future ShulkerBullet rendering tweaks.
 * Spark-specific rendering is now handled by PeekabooSparkEntityRenderer.
 */
@Mixin(ShulkerBulletEntityRenderer.class)
public abstract class ShulkerBulletEntityRendererMixin {
}
