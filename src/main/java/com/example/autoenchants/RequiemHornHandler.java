package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.particle.ShriekParticleEffect;
import net.minecraft.particle.VibrationParticleEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.event.BlockPositionSource;
import net.minecraft.world.event.PositionSource;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RequiemHornHandler {
    private static final Map<UUID, ActiveRequiem> ACTIVE = new HashMap<>();

    private RequiemHornHandler() {
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            UUID playerId = player.getUuid();
            ItemStack stack = player.getMainHandStack();
            int level = EnchantmentHelper.getLevel(AutoEnchantsMod.REQUIEM, stack);
            boolean usingRequiemHorn = player.isUsingItem() && stack.isOf(Items.GOAT_HORN) && level > 0;

            if (usingRequiemHorn) {
                ActiveRequiem active = ACTIVE.computeIfAbsent(
                        playerId,
                        id -> new ActiveRequiem(level, getInstrumentId(stack), player.getServerWorld().getRegistryKey().getValue().toString())
                );
                active.level = level;
                active.instrumentId = getInstrumentId(stack);
                active.dimension = player.getServerWorld().getRegistryKey().getValue().toString();
                processRequiemTick(player, active);
            } else {
                ActiveRequiem ended = ACTIVE.remove(playerId);
                if (ended != null) {
                    finishRequiem(player, ended);
                }
            }
        }
    }

    private static void processRequiemTick(ServerPlayerEntity player, ActiveRequiem active) {
        ServerWorld world = player.getServerWorld();
        int chunkRange = 8 + active.level;
        double blockRange = chunkRange * 16.0d;
        long now = world.getTime();

        spawnPlayerRequiemAura(world, player);

        if (now < active.nextPulseTick) {
            return;
        }

        active.nextPulseTick = now + 10 + world.random.nextInt(16);

            for (HostileEntity target : world.getEntitiesByClass(
                    HostileEntity.class,
                    player.getBoundingBox().expand(blockRange),
                    entity -> entity.isAlive() && !entity.isSpectator() && entity.getId() != player.getId()
            )) {
            active.targetIds.add(target.getUuid());
            spawnVibration(world, player.getBlockPos(), target.getBlockPos());
            applyHornEffect(active.instrumentId, target, active.level);
        }
    }

    private static void finishRequiem(ServerPlayerEntity player, ActiveRequiem active) {
        ServerWorld world = player.getServerWorld();
        if (!world.getRegistryKey().getValue().toString().equals(active.dimension)) {
            return;
        }

        float damage = 4.0f + active.level * 2.0f;
        if (world.isNight()) {
            damage *= 2.0f;
        }

        for (UUID id : active.targetIds) {
            if (!(world.getEntity(id) instanceof LivingEntity target) || !target.isAlive()) {
                continue;
            }
            spawnSonicRain(world, target.getX(), target.getY(), target.getZ(), target.getHeight());
            target.damage(player.getDamageSources().sonicBoom(player), damage);
            knockTargetBack(player, target, active.level);
            world.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, target.getX(), target.getBodyY(0.6d), target.getZ(), 22, 0.35d, 0.35d, 0.35d, 0.01d);
            world.spawnParticles(ParticleTypes.ENCHANT, target.getX(), target.getBodyY(0.6d), target.getZ(), 26, 0.45d, 0.45d, 0.45d, 0.02d);
        }
    }

    private static void spawnVibration(ServerWorld world, BlockPos from, BlockPos to) {
        PositionSource destination = new BlockPositionSource(to);
        VibrationParticleEffect effect = new VibrationParticleEffect(destination, 12);
        world.spawnParticles(effect, from.getX() + 0.5d, from.getY() + 1.6d, from.getZ() + 0.5d, 1, 0.0d, 0.0d, 0.0d, 0.0d);
    }

    private static void spawnSonicRain(ServerWorld world, double x, double y, double z, float targetHeight) {
        int streaks = 7;
        for (int i = 0; i < streaks; i++) {
            double py = y + targetHeight + 5.5d - i * 0.9d;
            world.spawnParticles(ParticleTypes.SONIC_BOOM, x, py, z, 1, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    private static void spawnPlayerRequiemAura(ServerWorld world, ServerPlayerEntity player) {
        world.spawnParticles(
                ParticleTypes.NOTE,
                player.getX(),
                player.getY() + 1.75d,
                player.getZ(),
                3,
                0.25d,
                0.15d,
                0.25d,
                1.0d
        );

        for (int i = 0; i < 4; i++) {
            double y = player.getY() + 1.6d + i * 0.7d;
            world.spawnParticles(new ShriekParticleEffect(0), player.getX(), y, player.getZ(), 1, 0.15d, 0.05d, 0.15d, 0.0d);
        }
    }

    private static void knockTargetBack(ServerPlayerEntity player, LivingEntity target, int level) {
        Vec3d horizontal = target.getPos().subtract(player.getPos());
        horizontal = new Vec3d(horizontal.x, 0.0d, horizontal.z);
        if (horizontal.lengthSquared() < 1.0E-4d) {
            horizontal = player.getRotationVec(1.0f);
            horizontal = new Vec3d(horizontal.x, 0.0d, horizontal.z);
        }
        if (horizontal.lengthSquared() < 1.0E-4d) {
            return;
        }
        Vec3d push = horizontal.normalize().multiply(0.55d + 0.08d * level);
        target.setVelocity(target.getVelocity().add(push.x, 0.35d + 0.04d * level, push.z));
        target.velocityModified = true;
    }

    private static void applyHornEffect(String instrumentId, LivingEntity target, int level) {
        int duration = 40 + level * 20;
        switch (instrumentId) {
            case "minecraft:ponder_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, duration, 0, false, true, true));
            case "minecraft:sing_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, duration, 0, false, true, true));
            case "minecraft:seek_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, duration, 1, false, true, true));
            case "minecraft:feel_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, duration, 1, false, true, true));
            case "minecraft:admire_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, duration, 1, false, true, true));
            case "minecraft:call_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, duration, 0, false, true, true));
            case "minecraft:yearn_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, duration, 0, false, true, true));
            case "minecraft:dream_goat_horn" -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, duration, 0, false, true, true));
            default -> target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, duration, 0, false, true, true));
        }
    }

    private static String getInstrumentId(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null) {
            return "minecraft:ponder_goat_horn";
        }
        return nbt.getString("instrument");
    }

    private static final class ActiveRequiem {
        private int level;
        private String instrumentId;
        private String dimension;
        private long nextPulseTick;
        private final Set<UUID> targetIds = new HashSet<>();

        private ActiveRequiem(int level, String instrumentId, String dimension) {
            this.level = level;
            this.instrumentId = instrumentId;
            this.dimension = dimension;
            this.nextPulseTick = 0L;
        }
    }
}
