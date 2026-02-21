package com.example.autoenchants;

import com.example.autoenchants.mixin.CrossbowItemInvoker;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

import java.util.HashMap;
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

            int automaticLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.AUTOMATIC, stack);
            if (automaticLevel <= 0) {
                clearAutomaticState(uuid);
                continue;
            }

            Hand hand = player.getActiveHand();
            if (hand == null) {
                clearAutomaticState(uuid);
                continue;
            }

            World world = player.getWorld();
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
        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.TRIPLE_BURST, stack);
        if (level <= 0 || !CrossbowItem.isCharged(stack)) {
            return false;
        }

        NbtList chargedProjectiles = getChargedProjectiles(stack);
        if (chargedProjectiles == null || chargedProjectiles.isEmpty()) {
            return false;
        }

        UUID uuid = player.getUuid();
        if (BURSTS.containsKey(uuid)) {
            return true;
        }

        World world = player.getWorld();
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

        World world = player.getWorld();
        double now = world.getTime();
        if (now < state.nextTick) {
            return;
        }

        ItemStack stack = player.getStackInHand(state.hand);
        if (!(stack.getItem() instanceof CrossbowItem)) {
            clearBurstState(uuid);
            return;
        }

        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.TRIPLE_BURST, stack);
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

    private static boolean fireBurstShot(PlayerEntity player, ItemStack stack, Hand hand, World world, NbtList chargedProjectiles) {
        if (!setChargedProjectiles(stack, chargedProjectiles)) {
            return false;
        }
        float speed = CrossbowItemInvoker.autoenchants$getSpeed(stack);
        CrossbowItemInvoker.autoenchants$shootAll(world, player, hand, stack, speed, 1.0f);
        CrossbowItemInvoker.autoenchants$setCharged(stack, false);
        return true;
    }

    private static NbtList getChargedProjectiles(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains("ChargedProjectiles", NbtElement.LIST_TYPE)) {
            return null;
        }
        return nbt.getList("ChargedProjectiles", NbtElement.COMPOUND_TYPE).copy();
    }

    private static boolean setChargedProjectiles(ItemStack stack, NbtList chargedProjectiles) {
        if (chargedProjectiles == null || chargedProjectiles.isEmpty()) {
            return false;
        }
        NbtCompound nbt = stack.getOrCreateNbt();
        nbt.putBoolean("Charged", true);
        nbt.put("ChargedProjectiles", chargedProjectiles.copy());
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
        private final NbtList chargedProjectiles;

        private BurstState(Hand hand, int remainingShots, double nextTick, NbtList chargedProjectiles) {
            this.hand = hand;
            this.remainingShots = remainingShots;
            this.nextTick = nextTick;
            this.chargedProjectiles = chargedProjectiles;
        }
    }
}
