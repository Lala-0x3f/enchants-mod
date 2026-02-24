package com.example.autoenchants.mixin.client;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.client.render.entity.ShulkerBulletEntityRenderer;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShulkerBulletEntityRenderer.class)
public abstract class ShulkerBulletEntityRendererMixin {
    private static final Identifier PEEKABOO_SHELL_SPARK_TEXTURE = AutoEnchantsMod.id("textures/entity/peekaboo_shell/peekaboo_shell_spark.png");

    @Inject(method = "getTexture(Lnet/minecraft/entity/projectile/ShulkerBulletEntity;)Lnet/minecraft/util/Identifier;", at = @At("RETURN"), cancellable = true)
    private void autoenchants$swapSparkTexture(ShulkerBulletEntity entity, CallbackInfoReturnable<Identifier> cir) {
        if (entity.getCommandTags().contains(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG)) {
            cir.setReturnValue(PEEKABOO_SHELL_SPARK_TEXTURE);
        }
    }

    @Inject(method = "getBlockLight(Lnet/minecraft/entity/projectile/ShulkerBulletEntity;Lnet/minecraft/util/math/BlockPos;)I", at = @At("RETURN"), cancellable = true)
    private void autoenchants$raiseSparkLight(ShulkerBulletEntity entity, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (entity.getCommandTags().contains(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG)) {
            cir.setReturnValue(Math.max(cir.getReturnValueI(), 10));
        }
    }
}
