package com.example.autoenchants.mixin;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.LockedOnHandler;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

@Mixin(TridentEntity.class)
public abstract class TridentEntityMixin {
    @Unique
    private static final double MIN_PEAK_HEIGHT = 15.0d;

    @Unique
    private double autoenchants$launchY = Double.NaN;
    @Unique
    private double autoenchants$maxY = Double.NEGATIVE_INFINITY;
    @Unique
    private UUID autoenchants$lockedTarget;
    @Unique
    private boolean autoenchants$bombardComplete = false;
    @Unique
    private boolean autoenchants$hasHitEntity = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void autoenchants$onTick(CallbackInfo ci) {
        TridentEntity self = (TridentEntity) (Object) this;
        World world = self.getEntityWorld();
        if (world.isClient()) {
            return;
        }

        ItemStack stack = self.getItemStack();
        int level = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.SKY_BOMBARD, stack);
        if (level <= 0) {
            return;
        }
        if (autoenchants$bombardComplete) {
            return;
        }
        if (self.getVelocity().lengthSquared() < 0.0025d) {
            autoenchants$lockedTarget = null;
            autoenchants$bombardComplete = true;
            return;
        }

        if (Double.isNaN(autoenchants$launchY)) {
            autoenchants$launchY = self.getY();
            autoenchants$maxY = self.getY();
        }
        autoenchants$maxY = Math.max(autoenchants$maxY, self.getY());

        Vec3d velocity = self.getVelocity();
        boolean descending = velocity.y < -0.03d;
        if (!descending || autoenchants$maxY - autoenchants$launchY < MIN_PEAK_HEIGHT) {
            return;
        }

        LivingEntity target = autoenchants$getOrAcquireTarget(self, level);
        if (target == null || !target.isAlive()) {
            return;
        }
        // 锁定状态优先目标并持续保活。
        LockedOnHandler.applyLockedAndGlow(target, 20);

        Vec3d toTarget = target.getEntityPos().add(0.0d, target.getHeight() * 0.6d, 0.0d).subtract(self.getEntityPos());
        if (toTarget.lengthSquared() < 1.0E-5d) {
            return;
        }
        Vec3d desiredDir = toTarget.normalize();
        double speed = Math.max(1.25d, velocity.length() + 0.05d + 0.03d * level);
        Vec3d guidedVelocity = velocity.multiply(0.78d).add(desiredDir.multiply(speed * 0.22d));
        self.setVelocity(guidedVelocity);
        self.velocityModified = true;

        if (world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.FIREWORK, self.getX(), self.getY(), self.getZ(), 4, 0.08d, 0.08d, 0.08d, 0.01d);
            for (int i = 0; i < 5; i++) {
                double py = self.getY() - i * 0.25d;
                serverWorld.spawnParticles(ParticleTypes.DRIPPING_WATER, self.getX(), py, self.getZ(), 1, 0.03d, 0.02d, 0.03d, 0.0d);
            }
        }
    }

    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void autoenchants$onEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        TridentEntity self = (TridentEntity) (Object) this;
        World world = self.getEntityWorld();
        autoenchants$lockedTarget = null;
        autoenchants$bombardComplete = true;
        if (world.isClient()) {
            return;
        }

        ItemStack stack = self.getItemStack();
        int level = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.SKY_BOMBARD, stack);
        if (level > 0) {
            Entity ownerEntity = self.getOwner();
            if (ownerEntity instanceof LivingEntity owner) {
                double baseDamage = 4.0d + level * 2.0d;
                boolean wetBoost = world.isRaining() || world.isThundering();
                if (wetBoost) {
                    baseDamage *= 2.0d;
                }

                List<LivingEntity> victims = world.getEntitiesByClass(
                        LivingEntity.class,
                        self.getBoundingBox().expand(3.0d),
                        entity -> entity.isAlive() && entity != owner
                );

                for (LivingEntity victim : victims) {
                    victim.damage((ServerWorld) world, owner.getDamageSources().trident(self, owner), (float) baseDamage);
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100 + level * 20, 0, false, true, true));
                    Vec3d knockDir = victim.getEntityPos().subtract(self.getEntityPos());
                    if (knockDir.lengthSquared() < 1.0E-5d) {
                        knockDir = new Vec3d(0.0d, 1.0d, 0.0d);
                    } else {
                        knockDir = knockDir.normalize();
                    }
                    double knockStrength = 0.8d + 0.15d * level;
                    victim.addVelocity(knockDir.x * knockStrength, 0.35d + 0.08d * level, knockDir.z * knockStrength);
                    victim.velocityModified = true;
                    if (world instanceof ServerWorld serverWorld) {
                        serverWorld.spawnParticles(ParticleTypes.EXPLOSION, victim.getX(), victim.getBodyY(0.5d), victim.getZ(), 2, 0.22d, 0.22d, 0.22d, 0.01d);
                        serverWorld.spawnParticles(ParticleTypes.SPLASH, victim.getX(), victim.getBodyY(0.5d), victim.getZ(), 14, 0.25d, 0.25d, 0.25d, 0.01d);
                        serverWorld.spawnParticles(ParticleTypes.SNEEZE, victim.getX(), victim.getBodyY(0.5d), victim.getZ(), 10, 0.18d, 0.18d, 0.18d, 0.01d);
                    }
                }
                float explosionPower = 1.0f + 0.2f * level;
                world.createExplosion(owner, self.getX(), self.getY(), self.getZ(), explosionPower, false, World.ExplosionSourceType.MOB);

                if (AutoEnchantsMod.getEnchantmentLevel(Enchantments.CHANNELING, stack) > 0 && world instanceof ServerWorld serverWorld) {
                    for (LivingEntity victim : victims) {
                        LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(serverWorld, net.minecraft.entity.SpawnReason.TRIGGERED);
                        if (lightning == null) {
                            continue;
                        }
                        lightning.refreshPositionAfterTeleport(victim.getX(), victim.getY(), victim.getZ());
                        lightning.setChanneler(owner instanceof net.minecraft.server.network.ServerPlayerEntity sp ? sp : null);
                        world.spawnEntity(lightning);
                        victim.setOnFireFor(5);
                    }
                }
            }
        }

        // 爆炸三叉戟附魔效果
        int explosiveLevel = AutoEnchantsMod.getEnchantmentLevel(AutoEnchantsMod.EXPLOSIVE_TRIDENT, stack);
        if (explosiveLevel > 0 && !autoenchants$hasHitEntity) {
            autoenchants$hasHitEntity = true;
            Entity hitEntity = hitResult.getEntity();
            
            // 检查是否命中实体（不是干草堆、水或标靶）
            if (hitEntity instanceof LivingEntity) {
                Entity ownerEntity = self.getOwner();
                if (ownerEntity instanceof LivingEntity owner) {
                    // 创建爆炸，威力为 4+lv
                    float explosionPower = 4.0f + explosiveLevel;
                    world.createExplosion(owner, self.getX(), self.getY(), self.getZ(), explosionPower, false, World.ExplosionSourceType.MOB);
                    
                    if (world instanceof ServerWorld serverWorld) {
                        // 生成大量向四周抛物线式溅射的粒子效果
                        Random random = world.getRandom();
                        for (int i = 0; i < 80 + explosiveLevel * 20; i++) {
                            double angle = random.nextDouble() * Math.PI * 2;
                            double pitch = random.nextDouble() * Math.PI * 0.5; // 0到90度
                            double speed = 0.3 + random.nextDouble() * 0.8;
                            
                            double vx = Math.cos(angle) * Math.cos(pitch) * speed;
                            double vy = Math.sin(pitch) * speed * 1.5; // 向上的速度更大
                            double vz = Math.sin(angle) * Math.cos(pitch) * speed;
                            
                            serverWorld.spawnParticles(ParticleTypes.FLAME, 
                                self.getX(), self.getY(), self.getZ(), 
                                0, vx, vy, vz, 1.0);
                            serverWorld.spawnParticles(ParticleTypes.LAVA, 
                                self.getX(), self.getY(), self.getZ(), 
                                0, vx, vy, vz, 1.0);
                        }
                        
                        // 额外的爆炸粒子
                        serverWorld.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, 
                            self.getX(), self.getY(), self.getZ(), 
                            1, 0, 0, 0, 0);
                    }
                    
                    // 点燃周围方块
                    autoenchants$igniteNearbyBlocks(world, self.getX(), self.getY(), self.getZ(), explosiveLevel);
                }
            }
        }
    }

    @Unique
    private boolean autoenchants$hasLineOfSight(TridentEntity self, LivingEntity target) {
        World world = self.getEntityWorld();
        Vec3d start = self.getEntityPos();
        Vec3d end = target.getEntityPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);
        
        HitResult hitResult = world.raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                self
        ));
        
        return hitResult.getType() == HitResult.Type.MISS;
    }

    @Unique
    private LivingEntity autoenchants$getOrAcquireTarget(TridentEntity self, int level) {
        World world = self.getEntityWorld();
        if (autoenchants$lockedTarget != null) {
            Entity existing = ((ServerWorld) world).getEntity(autoenchants$lockedTarget);
            if (existing instanceof LivingEntity living && living.isAlive()) {
                // 检查视线，如果失去视线则重新选择目标
                if (autoenchants$hasLineOfSight(self, living)) {
                    return living;
                }
                // 失去视线，清除锁定
                autoenchants$lockedTarget = null;
            } else {
                autoenchants$lockedTarget = null;
            }
        }

        Vec3d forward = self.getVelocity();
        if (forward.lengthSquared() < 1.0E-5d) {
            return null;
        }
        forward = forward.normalize();

        if (world instanceof ServerWorld serverWorld) {
            LivingEntity lockedTarget = LockedOnHandler.findBestLockedTargetInCone(
                    serverWorld,
                    self.getEntityPos(),
                    forward,
                    26.0d + level * 8.0d,
                    50.0d,
                    self.getOwner()
            );
            // 检查视线
            if (lockedTarget != null && autoenchants$hasLineOfSight(self, lockedTarget)) {
                autoenchants$lockedTarget = lockedTarget.getUuid();
                return lockedTarget;
            }
        }

        double range = 22.0d + level * 10.0d;
        List<HostileEntity> candidates = world.getEntitiesByClass(
                HostileEntity.class,
                self.getBoundingBox().expand(range),
                entity -> entity.isAlive() && !entity.isSpectator()
        );

        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (HostileEntity candidate : candidates) {
            Vec3d to = candidate.getEntityPos().add(0.0d, candidate.getHeight() * 0.5d, 0.0d).subtract(self.getEntityPos());
            if (to.lengthSquared() < 1.0E-5d) {
                continue;
            }
            Vec3d dir = to.normalize();
            double alignment = forward.dotProduct(dir);
            if (alignment < 0.45d) {
                continue;
            }
            // 检查视线，只考虑可见的目标
            if (!autoenchants$hasLineOfSight(self, candidate)) {
                continue;
            }
            double distancePenalty = to.length();
            double score = alignment * 120.0d - distancePenalty;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best != null) {
            autoenchants$lockedTarget = best.getUuid();
        }
        return best;
    }

    @Unique
    private void autoenchants$igniteNearbyBlocks(World world, double x, double y, double z, int level) {
        Random random = world.getRandom();
        int radius = 2 + level;
        BlockPos center = BlockPos.ofFloored(x, y, z);
        
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) {
                        continue;
                    }
                    
                    // 概率点燃，等级越高概率越大
                    if (random.nextFloat() > 0.15f + level * 0.1f) {
                        continue;
                    }
                    
                    BlockPos pos = center.add(dx, dy, dz);
                    BlockPos above = pos.up();
                    
                    // 只在空气方块上放置火
                    if (world.getBlockState(above).isAir() && world.getBlockState(pos).isSideSolidFullSquare(world, pos, net.minecraft.util.math.Direction.UP)) {
                        world.setBlockState(above, Blocks.FIRE.getDefaultState());
                    }
                }
            }
        }
    }
}
