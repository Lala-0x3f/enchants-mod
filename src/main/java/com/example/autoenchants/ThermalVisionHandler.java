package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.EndermanEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.AxolotlEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.FishEntity;
import net.minecraft.entity.passive.FrogEntity;
import net.minecraft.entity.passive.GlowSquidEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.passive.SquidEntity;
import net.minecraft.entity.passive.TadpoleEntity;
import net.minecraft.entity.passive.TurtleEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ThermalVisionHandler {
    private static final Map<UUID, Map<Integer, Long>> NEXT_PARTICLE_TICK = new HashMap<>();

    private ThermalVisionHandler() {
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerId = player.getUuid();
            ServerWorld world = player.getServerWorld();
            ItemStack helmet = player.getInventory().getArmorStack(3);
            int level = EnchantmentHelper.getLevel(AutoEnchantsMod.THERMAL_HELMET, helmet);

            if (level <= 0 || !world.isNight()) {
                NEXT_PARTICLE_TICK.remove(playerId);
                continue;
            }

            int chunkRange = 6 + level;
            double blockRange = chunkRange * 16.0d;
            long now = world.getTime();

            Map<Integer, Long> schedule = NEXT_PARTICLE_TICK.computeIfAbsent(playerId, id -> new HashMap<>());
            Set<Integer> nearbyIds = new HashSet<>();

            for (LivingEntity entity : world.getEntitiesByClass(
                    LivingEntity.class,
                    player.getBoundingBox().expand(blockRange),
                    e -> autoenchants$isThermalTarget(e, player)
            )) {
                int entityId = entity.getId();
                nearbyIds.add(entityId);
                Long nextTick = schedule.get(entityId);
                if (nextTick == null) {
                    schedule.put(entityId, now + autoenchants$nextDelay(world));
                    continue;
                }
                if (now < nextTick) {
                    continue;
                }

                world.spawnParticles(
                        ParticleTypes.GLOW,
                        entity.getX(),
                        entity.getBodyY(0.55d),
                        entity.getZ(),
                        7,
                        entity.getWidth() * 0.45d,
                        entity.getHeight() * 0.45d,
                        entity.getWidth() * 0.45d,
                        0.001d
                );

                schedule.put(entityId, now + autoenchants$nextDelay(world));
            }

            schedule.keySet().removeIf(id -> !nearbyIds.contains(id));
            if (schedule.isEmpty()) {
                NEXT_PARTICLE_TICK.remove(playerId);
            }
        }
    }

    private static boolean autoenchants$isThermalTarget(LivingEntity entity, PlayerEntity player) {
        if (!entity.isAlive() || entity == player || entity.isSpectator()) {
            return false;
        }

        // Exclude cold-blooded / aquatic or insect-like entities.
        if (entity instanceof FishEntity
                || entity instanceof AxolotlEntity
                || entity instanceof FrogEntity
                || entity instanceof TadpoleEntity
                || entity instanceof TurtleEntity
                || entity instanceof SquidEntity
                || entity instanceof GlowSquidEntity
                || entity instanceof BeeEntity) {
            return false;
        }

        // Exclude special constructs or special non-thermal targets.
        if (entity instanceof IronGolemEntity
                || entity instanceof SnowGolemEntity
                || entity instanceof EndermanEntity) {
            return false;
        }

        // Keep common warm/active targets visible.
        return entity instanceof PlayerEntity
                || entity instanceof HostileEntity
                || entity instanceof VillagerEntity
                || entity instanceof AnimalEntity
                || entity instanceof MobEntity;
    }

    private static int autoenchants$nextDelay(ServerWorld world) {
        return 10 + world.random.nextInt(16);
    }
}
