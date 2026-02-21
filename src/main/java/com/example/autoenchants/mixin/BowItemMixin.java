package com.example.autoenchants.mixin;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BowItem.class)
public abstract class BowItemMixin {
    @Redirect(
            method = "onStoppedUsing",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/projectile/PersistentProjectileEntity;setVelocity(Lnet/minecraft/entity/Entity;FFFFF)V"
            )
    )
    private void autoenchants$boostBowVelocity(
            PersistentProjectileEntity projectile,
            Entity shooter,
            float pitch,
            float yaw,
            float roll,
            float speed,
            float divergence,
            ItemStack stack,
            World world,
            LivingEntity user,
            int remainingUseTicks
    ) {
        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.PRECISE_SHOOTER, stack);
        float boostedSpeed = speed * AutoEnchantsMod.getPreciseShooterMultiplier(level);
        projectile.setVelocity(shooter, pitch, yaw, roll, boostedSpeed, divergence);
    }
}
