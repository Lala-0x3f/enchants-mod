package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
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
    private static final int AIM_GRACE_TICKS = 6;
    private static final double AIM_GRACE_CONE_COS = Math.cos(Math.toRadians(8.0d));
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
        // 状态效果和发光仅作为视觉提示，对效果免疫实体（凋零/末影龙）会静默失败
        target.addStatusEffect(new StatusEffectInstance(AutoEnchantsMod.LOCKED_ON, durationTicks, 0, false, true, true));
        target.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, durationTicks, 0, false, false, true));
        // LOCKED_STATES 是锁定状态的唯一真相来源，无论状态效果是否成功施加
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

    /**
     * 检查实体是否处于被锁定状态。基于 LOCKED_STATES Map 判断，
     * 不依赖状态效果，因此对效果免疫实体（凋零/末影龙）也有效。
     */
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
                    || candidate == excluded) {
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

            UUID playerId = player.getUuid();
            LivingEntity target = raycastCenteredLivingTarget(player, 96.0d);
            AimState state = AIM_STATES.get(playerId);

            if (target != null) {
                // 射线命中了目标
                UUID targetId = target.getUuid();
                if (state != null && state.targetId().equals(targetId)) {
                    state = new AimState(targetId, state.ticks() + 1, 0);
                } else {
                    state = new AimState(targetId, 1, 0);
                }
            } else if (state != null && state.graceTicks() < AIM_GRACE_TICKS) {
                // 射线未命中，但在宽限期内：检查之前的目标是否仍在视野大致方向
                ServerWorld world = player.getServerWorld();
                Entity prevEntity = world.getEntity(state.targetId());
                if (prevEntity instanceof LivingEntity prevTarget && prevTarget.isAlive() && !prevTarget.isSpectator()) {
                    Vec3d start = player.getCameraPosVec(1.0f);
                    Vec3d direction = player.getRotationVec(1.0f).normalize();
                    Vec3d toTarget = prevTarget.getPos().add(0.0d, prevTarget.getHeight() * 0.5d, 0.0d).subtract(start);
                    double distSq = toTarget.lengthSquared();
                    if (distSq > 1.0d && distSq <= 96.0d * 96.0d) {
                        Vec3d toTargetNorm = toTarget.normalize();
                        double dot = direction.dotProduct(toTargetNorm);
                        if (dot >= AIM_GRACE_CONE_COS) {
                            // 目标仍大致在视野方向，保持瞄准进度但增加宽限计数
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

            // 锁定完成，获取最终目标实体
            ServerWorld world = player.getServerWorld();
            Entity finalEntity = world.getEntity(state.targetId());
            if (finalEntity instanceof LivingEntity finalTarget && finalTarget.isAlive()) {
                int durationTicks = (10 + level * 2) * 20;
                applyLockedAndGlow(finalTarget, durationTicks);
                player.getItemCooldownManager().set(activeStack.getItem(), SPYGLASS_COOLDOWN_TICKS);
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
        Vec3d direction = player.getRotationVec(1.0f).normalize();

        // 使用宽松的圆锥检测代替精确射线，半角约 2.5 度
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
            // 检测到实体中心（眼睛高度的中点）
            Vec3d entityCenter = living.getPos().add(0.0d, living.getHeight() * 0.5d, 0.0d);
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

            // 对实体碰撞箱进行额外的扩展检测（对小型和飞行生物更宽容）
            double entityRadius = Math.max(living.getWidth(), living.getHeight()) * 0.5d;
            double angularSize = Math.atan2(entityRadius, dist);
            // 最小角度容差 1.5 度，确保远距离小目标也能被选中
            double minAngularTolerance = Math.toRadians(1.5d);
            double effectiveAngularSize = Math.max(angularSize, minAngularTolerance);
            double effectiveCos = Math.cos(effectiveAngularSize);

            // 射线到实体中心的角度必须在有效角度内
            if (dot < effectiveCos) {
                // 不在有效碰撞范围内，但仍在圆锥内，降低优先级
                double score = dot * 500.0d - dist;
                if (score > bestScore && best == null) {
                    // 仅在没有更好候选时作为备选
                }
                continue;
            }

            // 视线遮挡检测：检测到实体中心而非碰撞箱边缘
            BlockHitResult blockHit = world.raycast(new RaycastContext(
                    start,
                    entityCenter,
                    RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE,
                    player
            ));
            if (blockHit.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                // 如果中心被遮挡，尝试检测到实体眼睛位置
                Vec3d entityEye = living.getEyePos();
                BlockHitResult blockHit2 = world.raycast(new RaycastContext(
                        start,
                        entityEye,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        player
                ));
                if (blockHit2.getType() != net.minecraft.util.hit.HitResult.Type.MISS) {
                    continue;
                }
            }

            // 评分：优先对齐度，其次距离
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
