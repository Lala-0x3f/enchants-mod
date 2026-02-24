package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LockedOnHandler {
    private static final int AIM_REQUIRED_TICKS = 30;
    private static final int SPYGLASS_COOLDOWN_TICKS = 100;
    private static final int TARGET_POINTER_REFRESH_INTERVAL_TICKS = 20;
    private static final int TRAIL_PARTICLE_INTERVAL_TICKS = 2;
    private static final double LOCK_TRAIL_MIN_MOVEMENT_SQ = 0.0025d;
    private static final double LOCK_PARTICLE_RANGE = 128.0d;
    private static final String NBT_LODESTONE_POS = "LodestonePos";
    private static final String NBT_LODESTONE_DIM = "LodestoneDimension";
    private static final String NBT_LODESTONE_TRACKED = "LodestoneTracked";
    private static final Map<UUID, AimState> AIM_STATES = new HashMap<>();
    private static final Map<UUID, LockedState> LOCKED_STATES = new HashMap<>();

    private LockedOnHandler() {
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        tickSpyglassGuidance(server, now);
        tickLockedTrail(server, now);
        tickTargetPointers(server, now);
    }

    public static void applyLockedAndGlow(LivingEntity target, int durationTicks) {
        target.addStatusEffect(new StatusEffectInstance(AutoEnchantsMod.LOCKED_ON, durationTicks, 0, false, true, true));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, durationTicks, 0, false, false, true));
        if (target.getWorld() instanceof ServerWorld serverWorld) {
            LOCKED_STATES.put(
                    target.getUuid(),
                    new LockedState(
                            serverWorld.getRegistryKey(),
                            target.getPos(),
                            serverWorld.getServer().getTicks() + durationTicks,
                            serverWorld.getServer().getTicks()
                    )
            );
        }
    }

    public static LivingEntity findNearestLockedTarget(ServerWorld world, Vec3d origin, double range) {
        double maxDistanceSq = range * range;
        LivingEntity best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        for (Map.Entry<UUID, LockedState> entry : LOCKED_STATES.entrySet()) {
            LockedState state = entry.getValue();
            if (state.worldKey() != world.getRegistryKey()) {
                continue;
            }
            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity candidate) || !candidate.isAlive() || !candidate.hasStatusEffect(AutoEnchantsMod.LOCKED_ON)) {
                continue;
            }
            double distanceSq = candidate.getPos().squaredDistanceTo(origin);
            if (distanceSq > maxDistanceSq) {
                continue;
            }
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = candidate;
            }
        }
        return best;
    }

    public static LivingEntity findBestLockedTargetInCone(ServerWorld world, Vec3d origin, Vec3d forward, double range, double halfAngleDegrees, Entity excluded) {
        double cosThreshold = Math.cos(Math.toRadians(halfAngleDegrees));
        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        Vec3d baseForward = forward.normalize();
        for (Map.Entry<UUID, LockedState> entry : LOCKED_STATES.entrySet()) {
            LockedState state = entry.getValue();
            if (state.worldKey() != world.getRegistryKey()) {
                continue;
            }
            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity candidate)
                    || !candidate.isAlive()
                    || candidate.isSpectator()
                    || candidate == excluded
                    || !candidate.hasStatusEffect(AutoEnchantsMod.LOCKED_ON)) {
                continue;
            }
            Vec3d toCandidate = candidate.getPos().add(0.0d, candidate.getHeight() * 0.5d, 0.0d).subtract(origin);
            if (toCandidate.lengthSquared() < 1.0E-6d) {
                continue;
            }
            double distance = toCandidate.length();
            if (distance > range) {
                continue;
            }
            Vec3d dir = toCandidate.normalize();
            double alignment = baseForward.dotProduct(dir);
            if (alignment < cosThreshold) {
                continue;
            }
            // 优先中心对齐，再考虑距离。
            double score = alignment * 1000.0d - distance;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void tickSpyglassGuidance(MinecraftServer server, long now) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.isAlive() || player.isSpectator()) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }
            if (!player.isUsingItem()) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }
            ItemStack activeStack = player.getActiveItem();
            if (!activeStack.isOf(Items.SPYGLASS)) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }
            int level = EnchantmentHelper.getLevel(AutoEnchantsMod.GUIDANCE, activeStack);
            if (level <= 0) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }
            if (player.getItemCooldownManager().isCoolingDown(activeStack.getItem())) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }

            LivingEntity target = raycastCenteredLivingTarget(player, 96.0d);
            if (target == null) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }

            UUID playerId = player.getUuid();
            UUID targetId = target.getUuid();
            AimState state = AIM_STATES.get(playerId);
            if (state != null && state.targetId().equals(targetId)) {
                state = new AimState(targetId, state.ticks() + 1);
            } else {
                state = new AimState(targetId, 1);
            }

            if (state.ticks() < AIM_REQUIRED_TICKS) {
                AIM_STATES.put(playerId, state);
                continue;
            }

            int durationTicks = (10 + level * 2) * 20;
            applyLockedAndGlow(target, durationTicks);
            player.getItemCooldownManager().set(activeStack.getItem(), SPYGLASS_COOLDOWN_TICKS);
            player.stopUsingItem();
            AIM_STATES.remove(playerId);
        }
    }

    private static void tickLockedTrail(MinecraftServer server, long now) {
        LOCKED_STATES.entrySet().removeIf(entry -> {
            UUID entityId = entry.getKey();
            LockedState state = entry.getValue();
            if (now > state.expireTick()) {
                return true;
            }
            ServerWorld world = server.getWorld(state.worldKey());
            if (world == null) {
                return true;
            }
            Entity entity = world.getEntity(entityId);
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                return true;
            }
            if (!living.hasStatusEffect(AutoEnchantsMod.LOCKED_ON)) {
                return true;
            }
            if (now < state.nextTrailTick()) {
                return false;
            }
            Vec3d currentPos = living.getPos();
            if (currentPos.squaredDistanceTo(state.lastPos()) >= LOCK_TRAIL_MIN_MOVEMENT_SQ) {
                world.spawnParticles(ParticleTypes.FIREWORK, living.getX(), living.getBodyY(0.5d), living.getZ(), 2, 0.08d, 0.08d, 0.08d, 0.01d);
                LOCKED_STATES.put(entityId, new LockedState(state.worldKey(), currentPos, state.expireTick(), now + TRAIL_PARTICLE_INTERVAL_TICKS));
                return false;
            }
            LOCKED_STATES.put(entityId, new LockedState(state.worldKey(), state.lastPos(), state.expireTick(), now + TRAIL_PARTICLE_INTERVAL_TICKS));
            return false;
        });
    }

    private static void tickTargetPointers(MinecraftServer server, long now) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (now % TARGET_POINTER_REFRESH_INTERVAL_TICKS != (player.getId() & (TARGET_POINTER_REFRESH_INTERVAL_TICKS - 1))) {
                continue;
            }
            updateTargetPointerStack(player, player.getMainHandStack());
            updateTargetPointerStack(player, player.getOffHandStack());
        }
    }

    private static void updateTargetPointerStack(ServerPlayerEntity player, ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != AutoEnchantsMod.TARGET_POINTER) {
            return;
        }
        ServerWorld world = player.getServerWorld();
        LivingEntity target = findNearestLockedTarget(world, player.getPos(), LOCK_PARTICLE_RANGE);
        NbtCompound nbt = stack.getOrCreateNbt();
        if (target == null) {
            if (nbt.contains(NBT_LODESTONE_POS) || nbt.contains(NBT_LODESTONE_DIM) || !nbt.contains(NBT_LODESTONE_TRACKED)) {
                nbt.remove(NBT_LODESTONE_POS);
                nbt.remove(NBT_LODESTONE_DIM);
                nbt.putBoolean(NBT_LODESTONE_TRACKED, false);
            }
            return;
        }

        nbt.put(NBT_LODESTONE_POS, NbtHelper.fromBlockPos(target.getBlockPos()));
        World.CODEC.encodeStart(NbtOps.INSTANCE, world.getRegistryKey()).result().ifPresent(dim -> nbt.put(NBT_LODESTONE_DIM, dim));
        nbt.putBoolean(NBT_LODESTONE_TRACKED, false);
    }

    private static LivingEntity raycastCenteredLivingTarget(ServerPlayerEntity player, double maxDistance) {
        ServerWorld world = player.getServerWorld();
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d direction = player.getRotationVec(1.0f);
        Vec3d end = start.add(direction.multiply(maxDistance));
        Box searchBox = player.getBoundingBox().stretch(direction.multiply(maxDistance)).expand(1.25d);

        EntityHitResult entityHit = net.minecraft.entity.projectile.ProjectileUtil.raycast(
                player,
                start,
                end,
                searchBox,
                entity -> entity instanceof LivingEntity living && living.isAlive() && living != player && !living.isSpectator(),
                maxDistance * maxDistance
        );
        if (entityHit == null || !(entityHit.getEntity() instanceof LivingEntity target)) {
            return null;
        }

        BlockHitResult blockHit = world.raycast(new RaycastContext(
                start,
                entityHit.getPos(),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));
        if (blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
            return null;
        }
        return target;
    }

    private record AimState(UUID targetId, int ticks) {
    }

    private record LockedState(net.minecraft.registry.RegistryKey<World> worldKey, Vec3d lastPos, long expireTick, long nextTrailTick) {
    }
}
