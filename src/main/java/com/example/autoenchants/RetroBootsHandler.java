package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class RetroBootsHandler {
    private static final double VELOCITY_THRESHOLD = -0.8d;
    private static final double GROUND_CHECK_DISTANCE = 4.0d;
    private static final double TRIGGER_GROUND_DISTANCE = 3.0d;
    private static final double RETRO_THRUST_FACTOR = 0.55d;
    private static final double MAX_THRUST = 1.0d;
    private static final float FALL_DISTANCE_FACTOR = 0.4f;
    private static final int COOLDOWN_TICKS = 600; // 30 seconds
    private static final int FLAME_COUNT = 30;
    private static final int SMOKE_COUNT = 20;
    private static final double CONE_HALF_ANGLE_COS = Math.cos(Math.toRadians(30.0d));

    private RetroBootsHandler() {
    }

    public static void tick(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            tickPlayer(player);
        }
    }

    private static void tickPlayer(ServerPlayerEntity player) {
        if (player.isSpectator() || player.isOnGround() || player.isFallFlying()) {
            return;
        }

        Vec3d velocity = player.getVelocity();
        if (velocity.y >= VELOCITY_THRESHOLD) {
            return;
        }

        ItemStack boots = player.getEquippedStack(EquipmentSlot.FEET);
        if (boots.isEmpty() || EnchantmentHelper.getLevel(AutoEnchantsMod.RETRO_BOOTS, boots) <= 0) {
            return;
        }

        if (player.hasStatusEffect(AutoEnchantsMod.RETRO_BOOTS_COOLDOWN)) {
            return;
        }

        double groundDistance = findGroundDistance(player);
        if (groundDistance < 0.0d || groundDistance > TRIGGER_GROUND_DISTANCE) {
            return;
        }

        // Apply retro thrust
        double thrust = Math.min(Math.abs(velocity.y) * RETRO_THRUST_FACTOR, MAX_THRUST);
        player.setVelocity(velocity.x, velocity.y + thrust, velocity.z);
        player.velocityModified = true;
        player.fallDistance *= FALL_DISTANCE_FACTOR;

        // Spawn cone-shaped jet particles
        spawnRetroParticles((ServerWorld) player.getWorld(), player.getX(), player.getY(), player.getZ());

        // Damage boots
        boots.damage(1, player, p -> p.sendEquipmentBreakStatus(EquipmentSlot.FEET));

        // Start cooldown
        player.addStatusEffect(new StatusEffectInstance(
                AutoEnchantsMod.RETRO_BOOTS_COOLDOWN, COOLDOWN_TICKS, 0, false, false, true));
    }

    /**
     * Returns the distance to the ground below the player, or -1 if no ground found within check range.
     */
    private static double findGroundDistance(ServerPlayerEntity player) {
        BlockPos.Mutable pos = new BlockPos.Mutable(
                MathHelper.floor(player.getX()),
                MathHelper.floor(player.getY()) - 1,
                MathHelper.floor(player.getZ()));
        int minY = MathHelper.floor(player.getY() - GROUND_CHECK_DISTANCE);
        while (pos.getY() >= minY) {
            if (player.getWorld().getBlockState(pos).isSideSolidFullSquare(player.getWorld(), pos, Direction.UP)) {
                return player.getY() - (pos.getY() + 1.0d);
            }
            pos.move(Direction.DOWN);
        }
        return -1.0d;
    }

    private static void spawnRetroParticles(ServerWorld world, double x, double y, double z) {
        // Downward axis for cone
        for (int i = 0; i < FLAME_COUNT; i++) {
            Vec3d dir = randomConeDown(world);
            double speed = 0.3d + world.random.nextDouble() * 0.2d;
            // count=0 mode: deltaX/Y/Z = direction, speed = magnitude
            world.spawnParticles(ParticleTypes.FLAME, x, y, z,
                    0, dir.x, dir.y, dir.z, speed);
        }
        for (int i = 0; i < SMOKE_COUNT; i++) {
            Vec3d dir = randomConeDown(world);
            double speed = 0.15d + world.random.nextDouble() * 0.15d;
            world.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z,
                    0, dir.x, dir.y, dir.z, speed);
        }
    }

    /**
     * Generates a random direction within a 30-degree half-angle cone pointing downward (0, -1, 0).
     */
    private static Vec3d randomConeDown(ServerWorld world) {
        for (int attempt = 0; attempt < 8; attempt++) {
            double dx = world.random.nextGaussian() * 0.4d;
            double dy = -(0.6d + world.random.nextDouble() * 0.4d);
            double dz = world.random.nextGaussian() * 0.4d;
            Vec3d candidate = new Vec3d(dx, dy, dz);
            double lenSq = candidate.lengthSquared();
            if (lenSq < 1.0E-6d) {
                continue;
            }
            candidate = candidate.normalize();
            // Check if within cone: dot product with (0, -1, 0) >= cos(30°)
            if (-candidate.y >= CONE_HALF_ANGLE_COS) {
                return candidate;
            }
        }
        return new Vec3d(0.0d, -1.0d, 0.0d);
    }
}
