package com.example.autoenchants;

import com.example.autoenchants.mixin.CrossbowItemInvoker;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ChargedProjectilesComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AutoFireHandler {
    private static final Map<UUID, Double> NEXT_FIRE_TICK = new HashMap<>();
    private static final Map<UUID, BurstState> BURSTS = new HashMap<>();

    private AutoFireHandler() {
    }

    public static void tick(MinecraftServer server) {
        for (PlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID uuid = player.getUuid();
            processBurst(player, uuid);

            if (!player.isUsingItem()) {
                clearAutomaticState(uuid);
                continue;
            }

            ItemStack stack = player.getActiveItem();
            if (!(stack.getItem() instanceof CrossbowItem)) {
                clearAutomaticState(uuid);
                continue;
            }

            int automaticLevel = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.AUTOMATIC, stack);
            if (automaticLevel <= 0) {
                clearAutomaticState(uuid);
                continue;
            }

            Hand hand = player.getActiveHand();
            if (hand == null) {
                clearAutomaticState(uuid);
                continue;
            }

            World world = player.getEntityWorld();
            double now = world.getTime();
            double interval = getIntervalTicks(automaticLevel);
            double nextTick = NEXT_FIRE_TICK.getOrDefault(uuid, now);
            if (now < nextTick) {
                continue;
            }

            if (!fireCrossbow(player, stack, hand, world)) {
                clearAutomaticState(uuid);
                continue;
            }

            player.stopUsingItem();
            player.setCurrentHand(hand);
            NEXT_FIRE_TICK.put(uuid, now + interval);
        }
    }

    public static boolean startTripleBurst(PlayerEntity player, Hand hand, ItemStack stack) {
        if (!(stack.getItem() instanceof CrossbowItem)) {
            return false;
        }
        int level = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.TRIPLE_BURST, stack);
        if (level <= 0 || !CrossbowItem.isCharged(stack)) {
            return false;
        }

        List<ItemStack> chargedProjectiles = getChargedProjectiles(stack);
        if (chargedProjectiles == null || chargedProjectiles.isEmpty()) {
            return false;
        }

        UUID uuid = player.getUuid();
        if (BURSTS.containsKey(uuid)) {
            return true;
        }

        World world = player.getEntityWorld();
        if (!fireBurstShot(player, stack, hand, world, chargedProjectiles)) {
            return false;
        }

        BURSTS.put(uuid, new BurstState(hand, 2, world.getTime() + getBurstIntervalTicks(level), chargedProjectiles));
        return true;
    }

    private static boolean fireCrossbow(PlayerEntity player, ItemStack stack, Hand hand, World world) {
        if (!CrossbowItem.isCharged(stack)) {
            boolean loaded = CrossbowItemInvoker.autoenchants$loadProjectiles(player, stack);
            if (!loaded) {
                player.stopUsingItem();
                return false;
            }
            CrossbowItemInvoker.autoenchants$setCharged(stack, true);
        }

        float speed = CrossbowItemInvoker.autoenchants$getSpeed(stack);
        CrossbowItemInvoker.autoenchants$shootAll(world, player, hand, stack, speed, 1.0f);
        CrossbowItemInvoker.autoenchants$setCharged(stack, false);
        return true;
    }

    private static void clearAutomaticState(UUID uuid) {
        NEXT_FIRE_TICK.remove(uuid);
    }

    private static void clearBurstState(UUID uuid) {
        BURSTS.remove(uuid);
    }

    private static void processBurst(PlayerEntity player, UUID uuid) {
        BurstState state = BURSTS.get(uuid);
        if (state == null) {
            return;
        }

        if (state.remainingShots <= 0) {
            clearBurstState(uuid);
            return;
        }

        World world = player.getEntityWorld();
        double now = world.getTime();
        if (now < state.nextTick) {
            return;
        }

        ItemStack stack = player.getStackInHand(state.hand);
        if (!(stack.getItem() instanceof CrossbowItem)) {
            clearBurstState(uuid);
            return;
        }

        int level = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.TRIPLE_BURST, stack);
        if (level <= 0) {
            clearBurstState(uuid);
            return;
        }

        if (!fireBurstShot(player, stack, state.hand, world, state.chargedProjectiles)) {
            clearBurstState(uuid);
            return;
        }

        state.remainingShots--;
        if (state.remainingShots <= 0) {
            clearBurstState(uuid);
            return;
        }
        state.nextTick = now + getBurstIntervalTicks(level);
    }

    private static boolean fireBurstShot(PlayerEntity player, ItemStack stack, Hand hand, World world, List<ItemStack> chargedProjectiles) {
        if (!setChargedProjectiles(stack, chargedProjectiles)) {
            return false;
        }
        float speed = CrossbowItemInvoker.autoenchants$getSpeed(stack);
        CrossbowItemInvoker.autoenchants$shootAll(world, player, hand, stack, speed, 1.0f);
        CrossbowItemInvoker.autoenchants$setCharged(stack, false);
        return true;
    }

    private static List<ItemStack> getChargedProjectiles(ItemStack stack) {
        ChargedProjectilesComponent component = stack.get(DataComponentTypes.CHARGED_PROJECTILES);
        if (component == null) {
            return null;
        }
        return List.copyOf(component.getProjectiles());
    }

    private static boolean setChargedProjectiles(ItemStack stack, List<ItemStack> chargedProjectiles) {
        if (chargedProjectiles == null || chargedProjectiles.isEmpty()) {
            return false;
        }
        stack.set(DataComponentTypes.CHARGED_PROJECTILES, ChargedProjectilesComponent.of(chargedProjectiles));
        return true;
    }

    private static double getIntervalTicks(int level) {
        if (level <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return switch (Math.max(1, Math.min(level, 5))) {
            case 1 -> 20.0d / 4.0d;
            case 2 -> 20.0d / 6.0d;
            case 3 -> 20.0d / 8.0d;
            case 4 -> 20.0d / 10.0d;
            default -> 20.0d / 12.0d;
        };
    }

    private static double getBurstIntervalTicks(int level) {
        return switch (Math.max(1, Math.min(level, 3))) {
            case 1 -> 5.0d;
            case 2 -> 4.0d;
            default -> 3.0d;
        };
    }

    private static final class BurstState {
        private final Hand hand;
        private int remainingShots;
        private double nextTick;
        private final List<ItemStack> chargedProjectiles;

        private BurstState(Hand hand, int remainingShots, double nextTick, List<ItemStack> chargedProjectiles) {
            this.hand = hand;
            this.remainingShots = remainingShots;
            this.nextTick = nextTick;
            this.chargedProjectiles = chargedProjectiles;
        }
    }
}
