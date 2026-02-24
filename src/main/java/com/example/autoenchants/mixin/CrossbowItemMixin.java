package com.example.autoenchants.mixin;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FireworkRocketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.VibrationParticleEffect;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.world.event.EntityPositionSource;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CrossbowItem.class)
public abstract class CrossbowItemMixin {
    private static final String BLAST_FIREWORK_TAG_PREFIX = "autoenchants_blast_firework_lv_";
    private static final String FIREWORK_SHULKER_TAG_PREFIX = "autoenchants_firework_shulker_lv_";
    private static final String FIREWORK_GOLEM_TAG = "autoenchants_firework_golem";
    private static final String FIREWORK_CREEPER_TAG = "autoenchants_firework_creeper";
    private static final String PRECISE_GUIDANCE_TAG = "autoenchants_precise_guidance";

    @Redirect(
            method = "loadProjectiles",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/LivingEntity;getProjectileType(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/item/ItemStack;"
            )
    )
    private static ItemStack autoenchants$enforceFireworkOnlyLoad(LivingEntity shooter, ItemStack crossbow) {
        ItemStack original = shooter.getProjectileType(crossbow);
        if (!autoenchants$requiresFireworkOnly(crossbow)) {
            return original;
        }
        if (!original.isEmpty() && original.isOf(Items.FIREWORK_ROCKET)) {
            return original;
        }
        return autoenchants$findFireworkProjectile(shooter);
    }

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
        int creeperLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.FIREWORK_CREEPER, crossbow);
        if (projectileStack.getItem() instanceof FireworkRocketItem && creeperLevel > 0) {
            projectile.addCommandTag(FIREWORK_CREEPER_TAG);
        }
        int guidanceLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.PRECISE_GUIDANCE, crossbow);
        if (projectileStack.getItem() instanceof FireworkRocketItem && guidanceLevel > 0) {
            projectile.addCommandTag(PRECISE_GUIDANCE_TAG);
            if (world instanceof ServerWorld serverWorld) {
                serverWorld.spawnParticles(
                        new VibrationParticleEffect(new EntityPositionSource(projectile, 0.0f), 10),
                        entity.getX(),
                        entity.getBodyY(0.8d),
                        entity.getZ(),
                        1,
                        0.0d,
                        0.0d,
                        0.0d,
                        0.0d
                );
            }
        }
        projectile.setVelocity(velocityX, velocityY, velocityZ, boostedSpeed, improvedDivergence);
    }

    private static boolean autoenchants$requiresFireworkOnly(ItemStack crossbow) {
        return EnchantmentHelper.getLevel(AutoEnchantsMod.BLAST_FIREWORK, crossbow) > 0
                || EnchantmentHelper.getLevel(AutoEnchantsMod.FIREWORK_SHULKER, crossbow) > 0
                || EnchantmentHelper.getLevel(AutoEnchantsMod.FIREWORK_GOLEM, crossbow) > 0
                || EnchantmentHelper.getLevel(AutoEnchantsMod.FIREWORK_CREEPER, crossbow) > 0
                || EnchantmentHelper.getLevel(AutoEnchantsMod.PRECISE_GUIDANCE, crossbow) > 0;
    }

    private static ItemStack autoenchants$findFireworkProjectile(LivingEntity shooter) {
        if (!(shooter instanceof PlayerEntity player)) {
            ItemStack offHand = shooter.getOffHandStack();
            if (offHand.isOf(Items.FIREWORK_ROCKET)) {
                return offHand;
            }
            ItemStack mainHand = shooter.getMainHandStack();
            if (mainHand.isOf(Items.FIREWORK_ROCKET)) {
                return mainHand;
            }
            return ItemStack.EMPTY;
        }

        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack candidate = player.getInventory().getStack(i);
            if (candidate.isOf(Items.FIREWORK_ROCKET)) {
                return candidate;
            }
        }
        return ItemStack.EMPTY;
    }
}
