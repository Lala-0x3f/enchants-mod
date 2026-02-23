package com.example.autoenchants.mixin;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    private static final String BLAST_FIREWORK_TAG_PREFIX = "autoenchants_blast_firework_lv_";
    private static final String FIREWORK_SHULKER_TAG_PREFIX = "autoenchants_firework_shulker_lv_";
    private static final String FIREWORK_GOLEM_TAG = "autoenchants_firework_golem";

    @Redirect(
            method = "shoot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/projectile/ProjectileEntity;setVelocity(DDDFF)V"
            )
    )
    private static void autoenchants$boostCrossbowVelocity(
            ProjectileEntity projectile,
            double velocityX,
            double velocityY,
            double velocityZ,
            float speed,
            float divergence,
            World world,
            LivingEntity entity,
            Hand hand,
            ItemStack crossbow,
            ItemStack projectileStack,
            float soundPitch,
            boolean creative,
            float speedArgument,
            float divergenceArgument,
            float simulated
    ) {
        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.PRECISE_SHOOTER, crossbow);
        float boostedSpeed = speed * AutoEnchantsMod.getPreciseShooterMultiplier(level);
        float improvedDivergence = AutoEnchantsMod.getPreciseShooterDivergence(divergence, level);
        int blastLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.BLAST_FIREWORK, crossbow);
        if (projectileStack.getItem() instanceof FireworkRocketItem && blastLevel > 0) {
            projectile.addCommandTag(BLAST_FIREWORK_TAG_PREFIX + blastLevel);
        }
        int shulkerLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.FIREWORK_SHULKER, crossbow);
        if (projectileStack.getItem() instanceof FireworkRocketItem && shulkerLevel > 0) {
            projectile.addCommandTag(FIREWORK_SHULKER_TAG_PREFIX + shulkerLevel);
        }
        int golemLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.FIREWORK_GOLEM, crossbow);
        if (projectileStack.getItem() instanceof FireworkRocketItem && golemLevel > 0) {
            projectile.addCommandTag(FIREWORK_GOLEM_TAG);
        }
        projectile.setVelocity(velocityX, velocityY, velocityZ, boostedSpeed, improvedDivergence);
    }
}
