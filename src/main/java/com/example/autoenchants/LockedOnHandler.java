package com.example.autoenchants;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LodestoneTrackerComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class LockedOnHandler {
    private static final int AIM_REQUIRED_TICKS = 30;
    private static final int AIM_GRACE_TICKS = 6;
    private static final double AIM_GRACE_CONE_COS = Math.cos(Math.toRadians(8.0d));
    private static final int SPYGLASS_COOLDOWN_TICKS = 100;
    private static final int TARGET_POINTER_REFRESH_INTERVAL_TICKS = 20;
    private static final int TRAIL_PARTICLE_INTERVAL_TICKS = 2;
    private static final double LOCK_TRAIL_MIN_MOVEMENT_SQ = 0.0025d;
    private static final double LOCK_PARTICLE_RANGE = 128.0d;
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
        RegistryEntry<net.minecraft.entity.effect.StatusEffect> lockedOnEffect = target.getEntityWorld()
                .getRegistryManager()
                .getOrThrow(net.minecraft.registry.RegistryKeys.STATUS_EFFECT)
                .getEntry(AutoEnchantsMod.LOCKED_ON);
        target.addStatusEffect(new StatusEffectInstance(lockedOnEffect, durationTicks, 0, false, true, true));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, durationTicks, 0, false, false, true));
        if (target.getEntityWorld() instanceof ServerWorld serverWorld) {
            LOCKED_STATES.put(
                    target.getUuid(),
                    new LockedState(
                            serverWorld.getRegistryKey(),
                            target.getEntityPos(),
                            serverWorld.getServer().getTicks() + durationTicks,
                            serverWorld.getServer().getTicks()
                    )
            );
        }
    }

    public static boolean isLockedOn(LivingEntity entity) {
        LockedState state = LOCKED_STATES.get(entity.getUuid());
        return state != null;
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
            if (!(entity instanceof LivingEntity candidate) || !candidate.isAlive()) {
                continue;
            }
            double distanceSq = candidate.getEntityPos().squaredDistanceTo(origin);
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

    public static List<LivingEntity> findAllLockedTargets(ServerWorld world, Vec3d origin, double range) {
        double maxDistanceSq = range * range;
        List<LivingEntity> targets = new java.util.ArrayList<>();
        for (Map.Entry<UUID, LockedState> entry : LOCKED_STATES.entrySet()) {
            LockedState state = entry.getValue();
            if (state.worldKey() != world.getRegistryKey()) {
                continue;
            }
            Entity entity = world.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity candidate) || !candidate.isAlive()) {
                continue;
            }
            double distanceSq = candidate.getEntityPos().squaredDistanceTo(origin);
            if (distanceSq <= maxDistanceSq) {
                targets.add(candidate);
            }
        }
        return targets;
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
                    || candidate == excluded) {
                continue;
            }
            Vec3d toCandidate = candidate.getEntityPos().add(0.0d, candidate.getHeight() * 0.5d, 0.0d).subtract(origin);
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
            int level = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.GUIDANCE, activeStack);
            if (level <= 0) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }
            if (player.getItemCooldownManager().isCoolingDown(activeStack)) {
                AIM_STATES.remove(player.getUuid());
                continue;
            }

            UUID playerId = player.getUuid();
            LivingEntity target = raycastCenteredLivingTarget(player, 96.0d);
            AimState state = AIM_STATES.get(playerId);

            if (target != null) {
                UUID targetId = target.getUuid();
                if (state != null && state.targetId().equals(targetId)) {
                    state = new AimState(targetId, state.ticks() + 1, 0);
                } else {
                    state = new AimState(targetId, 1, 0);
                }
            } else if (state != null && state.graceTicks() < AIM_GRACE_TICKS) {
                ServerWorld world = (ServerWorld) player.getEntityWorld();
                Entity prevEntity = world.getEntity(state.targetId());
                if (prevEntity instanceof LivingEntity prevTarget && prevTarget.isAlive() && !prevTarget.isSpectator()) {
                    Vec3d start = player.getCameraPosVec(1.0f);
                    Vec3d direction = player.getRotationVec(1.0f).normalize();
                    Vec3d toTarget = prevTarget.getEntityPos().add(0.0d, prevTarget.getHeight() * 0.5d, 0.0d).subtract(start);
                    double distSq = toTarget.lengthSquared();
                    if (distSq > 1.0d && distSq <= 96.0d * 96.0d) {
                        Vec3d toTargetNorm = toTarget.normalize();
                        double dot = direction.dotProduct(toTargetNorm);
                        if (dot >= AIM_GRACE_CONE_COS) {
                            state = new AimState(state.targetId(), state.ticks() + 1, state.graceTicks() + 1);
                        } else {
                            AIM_STATES.remove(playerId);
                            continue;
                        }
                    } else {
                        AIM_STATES.remove(playerId);
                        continue;
                    }
                } else {
                    AIM_STATES.remove(playerId);
                    continue;
                }
            } else {
                AIM_STATES.remove(playerId);
                continue;
            }

            if (state.ticks() < AIM_REQUIRED_TICKS) {
                AIM_STATES.put(playerId, state);
                continue;
            }

            ServerWorld world = (ServerWorld) player.getEntityWorld();
            Entity finalEntity = world.getEntity(state.targetId());
            if (finalEntity instanceof LivingEntity finalTarget && finalTarget.isAlive()) {
                int durationTicks = (10 + level * 2) * 20;
                applyLockedAndGlow(finalTarget, durationTicks);
                player.getItemCooldownManager().set(activeStack, SPYGLASS_COOLDOWN_TICKS);
                player.stopUsingItem();
            }
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
            if (now < state.nextTrailTick()) {
                return false;
            }
            Vec3d currentPos = living.getEntityPos();
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
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        LivingEntity target = findNearestLockedTarget(world, player.getEntityPos(), LOCK_PARTICLE_RANGE);
        if (target == null) {
            stack.remove(DataComponentTypes.LODESTONE_TRACKER);
            return;
        }

        stack.set(
                DataComponentTypes.LODESTONE_TRACKER,
                new LodestoneTrackerComponent(Optional.of(GlobalPos.create(world.getRegistryKey(), target.getBlockPos())), false)
        );
    }

    private static LivingEntity raycastCenteredLivingTarget(ServerPlayerEntity player, double maxDistance) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        Vec3d start = player.getCameraPosVec(1.0f);
        Vec3d direction = player.getRotationVec(1.0f).normalize();
        double coneCos = Math.cos(Math.toRadians(2.5d));
        double maxDistSq = maxDistance * maxDistance;

        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;

        Box searchBox = player.getBoundingBox().stretch(direction.multiply(maxDistance)).expand(8.0d);
        List<Entity> candidates = world.getOtherEntities(player, searchBox,
                entity -> entity instanceof LivingEntity living && living.isAlive() && !living.isSpectator());

        for (Entity entity : candidates) {
            if (!(entity instanceof LivingEntity living)) {
                continue;
            }
            Vec3d entityCenter = living.getEntityPos().add(0.0d, living.getHeight() * 0.5d, 0.0d);
            Vec3d toEntity = entityCenter.subtract(start);
            double distSq = toEntity.lengthSquared();
            if (distSq > maxDistSq || distSq < 1.0d) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            Vec3d toEntityNorm = toEntity.multiply(1.0d / dist);
            double dot = direction.dotProduct(toEntityNorm);
            if (dot < coneCos) {
                continue;
            }

            double entityRadius = Math.max(living.getWidth(), living.getHeight()) * 0.5d;
            double angularSize = Math.atan2(entityRadius, dist);
            double minAngularTolerance = Math.toRadians(1.5d);
            double effectiveAngularSize = Math.max(angularSize, minAngularTolerance);
            double effectiveCos = Math.cos(effectiveAngularSize);

            if (dot < effectiveCos) {
                continue;
            }

            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    start,
                    entityCenter,
                    RaycastContext.ShapeType.VISUAL,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));
            if (blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                Vec3d entityEye = living.getEyePos();
                BlockHitResult blockHit2 = world.raycast(new RaycastContext(
                        start,
                        entityEye,
                        RaycastContext.ShapeType.VISUAL,
                        RaycastContext.FluidHandling.NONE,
                        player
                ));
                if (blockHit2.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                    continue;
                }
            }

            double score = dot * 1000.0d - dist;
            if (score > bestScore) {
                bestScore = score;
                best = living;
            }
        }
        return best;
    }

    private record AimState(UUID targetId, int ticks, int graceTicks) {
    }

    private record LockedState(net.minecraft.registry.RegistryKey<World> worldKey, Vec3d lastPos, long expireTick, long nextTrailTick) {
    }
}
