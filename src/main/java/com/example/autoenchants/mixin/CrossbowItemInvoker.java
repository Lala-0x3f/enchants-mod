package com.example.autoenchants.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(CrossbowItem.class)
public interface CrossbowItemInvoker {
    @Invoker("loadProjectiles")
    static boolean autoenchants$loadProjectiles(LivingEntity shooter, ItemStack crossbow) {
        throw new AssertionError();
    }

    @Invoker("shootAll")
    static void autoenchants$shootAll(World world, LivingEntity shooter, Hand hand, ItemStack crossbow, float speed, float divergence) {
        throw new AssertionError();
    }

    @Invoker("setCharged")
    static void autoenchants$setCharged(ItemStack crossbow, boolean charged) {
        throw new AssertionError();
    }

    @Invoker("getPullTime")
    static int autoenchants$getPullTime(ItemStack stack) {
        throw new AssertionError();
    }

    @Invoker("getSpeed")
    static float autoenchants$getSpeed(ItemStack stack) {
        throw new AssertionError();
    }
}
