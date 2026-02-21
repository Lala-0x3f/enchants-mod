package com.example.autoenchants;

import com.example.autoenchants.enchant.AutomaticEnchantment;
import com.example.autoenchants.enchant.BlastFireworkEnchantment;
import com.example.autoenchants.enchant.PreciseShooterEnchantment;
import com.example.autoenchants.enchant.TripleBurstEnchantment;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;

public class AutoEnchantsMod implements ModInitializer {
    public static final String MOD_ID = "autoenchants";

    public static Enchantment PRECISE_SHOOTER;
    public static Enchantment AUTOMATIC;
    public static Enchantment TRIPLE_BURST;
    public static Enchantment BLAST_FIREWORK;

    @Override
    public void onInitialize() {
        PRECISE_SHOOTER = Registry.register(
                Registries.ENCHANTMENT,
                id("precise_shooter"),
                new PreciseShooterEnchantment()
        );

        AUTOMATIC = Registry.register(
                Registries.ENCHANTMENT,
                id("automatic"),
                new AutomaticEnchantment()
        );

        TRIPLE_BURST = Registry.register(
                Registries.ENCHANTMENT,
                id("triple_burst"),
                new TripleBurstEnchantment()
        );

        BLAST_FIREWORK = Registry.register(
                Registries.ENCHANTMENT,
                id("blast_firework"),
                new BlastFireworkEnchantment()
        );

        ServerTickEvents.END_SERVER_TICK.register(AutoFireHandler::tick);
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient || !(stack.getItem() instanceof CrossbowItem)) {
                return TypedActionResult.pass(stack);
            }
            if (AutoFireHandler.startTripleBurst(player, hand, stack)) {
                return TypedActionResult.success(stack);
            }
            return TypedActionResult.pass(stack);
        });
    }

    public static float getPreciseShooterMultiplier(int level) {
        if (level <= 0) {
            return 1.0f;
        }

        return 1.0f + (0.75f * level);
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }
}
