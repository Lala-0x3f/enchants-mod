package com.example.autoenchants.mixin;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.LockedOnHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
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
    private static final double AIRBURST_TRIGGER_HEIGHT = 2.75d;

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
    @Unique
    private boolean autoenchants$airburstTriggered = false;

    @Inject(method = "tick", at = @At("HEAD"))
    private void autoenchants$onTick(CallbackInfo ci) {
        TridentEntity self = (TridentEntity) (Object) this;
        World world = self.getWorld();
        if (world.isClient()) {
            return;
        }

        ItemStack stack = self.getItemStack();
        autoenchants$handleSkyBombard(self, stack);
        autoenchants$handleAirburstTrident(self, stack);
    }

    @Inject(method = "onEntityHit", at = @At("TAIL"))
    private void autoenchants$onEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        TridentEntity self = (TridentEntity) (Object) this;
        World world = self.getWorld();
        autoenchants$lockedTarget = null;
        autoenchants$bombardComplete = true;
        autoenchants$airburstTriggered = true;
        if (world.isClient()) {
            return;
        }

        ItemStack stack = self.getItemStack();
        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.SKY_BOMBARD, stack);
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
                    victim.damage(owner.getDamageSources().trident(self, owner), (float) baseDamage);
                    victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100 + level * 20, 0, false, true, true));
                    Vec3d knockDir = victim.getPos().subtract(self.getPos());
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

                if (EnchantmentHelper.getLevel(Enchantments.CHANNELING, stack) > 0 && world instanceof ServerWorld serverWorld) {
                    for (LivingEntity victim : victims) {
                        LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(world);
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

        int explosiveLevel = EnchantmentHelper.getLevel(AutoEnchantsMod.EXPLOSIVE_TRIDENT, stack);
        if (explosiveLevel > 0 && !autoenchants$hasHitEntity) {
            autoenchants$hasHitEntity = true;
            Entity hitEntity = hitResult.getEntity();
            if (hitEntity instanceof LivingEntity) {
                Entity ownerEntity = self.getOwner();
                if (ownerEntity instanceof LivingEntity owner) {
                    float explosionPower = 4.0f + explosiveLevel;
                    world.createExplosion(owner, self.getX(), self.getY(), self.getZ(), explosionPower, false, World.ExplosionSourceType.MOB);

                    if (world instanceof ServerWorld serverWorld) {
                        Random random = world.getRandom();
                        for (int i = 0; i < 80 + explosiveLevel * 20; i++) {
                            double angle = random.nextDouble() * Math.PI * 2;
                            double pitch = random.nextDouble() * Math.PI * 0.5;
                            double speed = 0.3 + random.nextDouble() * 0.8;

                            double vx = Math.cos(angle) * Math.cos(pitch) * speed;
                            double vy = Math.sin(pitch) * speed * 1.5;
                            double vz = Math.sin(angle) * Math.cos(pitch) * speed;

                            serverWorld.spawnParticles(ParticleTypes.FLAME,
                                    self.getX(), self.getY(), self.getZ(),
                                    0, vx, vy, vz, 1.0);
                            serverWorld.spawnParticles(ParticleTypes.LAVA,
                                    self.getX(), self.getY(), self.getZ(),
                                    0, vx, vy, vz, 1.0);
                        }

                        serverWorld.spawnParticles(ParticleTypes.EXPLOSION_EMITTER,
                                self.getX(), self.getY(), self.getZ(),
                                1, 0, 0, 0, 0);
                    }

                    autoenchants$igniteNearbyBlocks(world, self.getX(), self.getY(), self.getZ(), explosiveLevel);
                }
            }
        }
    }

    @Unique
    private void autoenchants$handleSkyBombard(TridentEntity self, ItemStack stack) {
        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.SKY_BOMBARD, stack);
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
        LockedOnHandler.applyLockedAndGlow(target, 20);

        Vec3d toTarget = target.getPos().add(0.0d, target.getHeight() * 0.6d, 0.0d).subtract(self.getPos());
        if (toTarget.lengthSquared() < 1.0E-5d) {
            return;
        }
        Vec3d desiredDir = toTarget.normalize();
        double speed = Math.max(1.25d, velocity.length() + 0.05d + 0.03d * level);
        Vec3d guidedVelocity = velocity.multiply(0.78d).add(desiredDir.multiply(speed * 0.22d));
        self.setVelocity(guidedVelocity);
        self.velocityModified = true;

        if (self.getWorld() instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.FIREWORK, self.getX(), self.getY(), self.getZ(), 4, 0.08d, 0.08d, 0.08d, 0.01d);
            for (int i = 0; i < 5; i++) {
                double py = self.getY() - i * 0.25d;
                serverWorld.spawnParticles(ParticleTypes.DRIPPING_WATER, self.getX(), py, self.getZ(), 1, 0.03d, 0.02d, 0.03d, 0.0d);
            }
        }
    }

    @Unique
    private void autoenchants$handleAirburstTrident(TridentEntity self, ItemStack stack) {
        int level = EnchantmentHelper.getLevel(AutoEnchantsMod.AIRBURST_TRIDENT, stack);
        if (level <= 0 || autoenchants$airburstTriggered) {
            return;
        }

        Vec3d velocity = self.getVelocity();
        if (velocity.lengthSquared() < 0.01d || velocity.y > -0.08d) {
            return;
        }

        BlockHitResult groundHit = autoenchants$findGroundAhead(self);
        if (groundHit == null) {
            return;
        }

        double distanceToGround = self.getPos().distanceTo(groundHit.getPos());
        double triggerHeight = AIRBURST_TRIGGER_HEIGHT + level * 0.75d;
        if (distanceToGround > triggerHeight) {
            return;
        }

        autoenchants$airburstTriggered = true;
        autoenchants$lockedTarget = null;
        autoenchants$bombardComplete = true;
        autoenchants$triggerAirburst(self, level, groundHit);
    }

    @Unique
    private void autoenchants$triggerAirburst(TridentEntity self, int level, BlockHitResult groundHit) {
        World world = self.getWorld();
        Entity ownerEntity = self.getOwner();
        if (!(world instanceof ServerWorld serverWorld) || !(ownerEntity instanceof LivingEntity owner)) {
            return;
        }

        Vec3d burstPos = groundHit.getPos().add(0.0d, 1.6d + level * 0.22d, 0.0d);
        float explosionPower = 6.5f + level * 2.0f;
        float damage = 16.0f + level * 6.0f;
        double radius = 6.5d + level * 2.0d;
        Box area = Box.of(burstPos, radius * 2.0d, radius * 2.0d, radius * 2.0d);

        serverWorld.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, burstPos.x, burstPos.y, burstPos.z, 2, 0.15d, 0.1d, 0.15d, 0.0d);
        serverWorld.spawnParticles(ParticleTypes.GLOW_SQUID_INK, burstPos.x, burstPos.y, burstPos.z, 70 + level * 18, 1.2d + level * 0.22d, 0.7d, 1.2d + level * 0.22d, 0.04d);
        serverWorld.spawnParticles(ParticleTypes.LANDING_HONEY, burstPos.x, burstPos.y, burstPos.z, 40 + level * 12, 1.25d + level * 0.24d, 0.45d, 1.25d + level * 0.24d, 0.03d);
        serverWorld.spawnParticles(ParticleTypes.LAVA, burstPos.x, burstPos.y, burstPos.z, 32 + level * 10, 1.0d + level * 0.18d, 0.4d, 1.0d + level * 0.18d, 0.02d);
        autoenchants$spawnAirburstJets(serverWorld, burstPos, level);
        autoenchants$destroyAirburstBlocks(world, burstPos, level);

        List<LivingEntity> victims = world.getEntitiesByClass(
                LivingEntity.class,
                area,
                entity -> entity.isAlive() && entity != owner
        );

        for (LivingEntity victim : victims) {
            Vec3d delta = victim.getPos().add(0.0d, victim.getStandingEyeHeight() * 0.35d, 0.0d).subtract(burstPos);
            double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            if (horizontalDistance > radius) {
                continue;
            }

            double distanceFactor = 1.0d - horizontalDistance / radius;
            float dealtDamage = (float) (damage * (0.72d + distanceFactor * 0.38d));
            victim.damage(owner.getDamageSources().explosion(owner, owner), dealtDamage);
            victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 80 + level * 30, 1, false, true, true));

            Vec3d knock = new Vec3d(delta.x, 0.22d, delta.z);
            if (knock.lengthSquared() < 1.0E-5d) {
                knock = new Vec3d(0.0d, 0.3d, 0.0d);
            } else {
                knock = knock.normalize();
            }
            double push = 1.4d + level * 0.3d + distanceFactor * 0.8d;
            victim.addVelocity(knock.x * push, 0.65d + level * 0.15d + distanceFactor * 0.3d, knock.z * push);
            victim.velocityModified = true;
        }

        world.createExplosion(owner, burstPos.x, burstPos.y, burstPos.z, explosionPower, false, World.ExplosionSourceType.MOB);
        autoenchants$igniteNearbyBlocks(world, burstPos.x, burstPos.y - 1.0d, burstPos.z, 1 + level);
        self.discard();
    }

    @Unique
    private void autoenchants$spawnAirburstJets(ServerWorld world, Vec3d burstPos, int level) {
        Random random = world.getRandom();
        int jetCount = 28 + level * 10;
        for (int i = 0; i < jetCount; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0d;
            double horizontalSpeed = 0.28d + random.nextDouble() * (0.4d + level * 0.07d);
            double upwardSpeed = 0.28d + random.nextDouble() * (0.28d + level * 0.06d);
            double vx = Math.cos(angle) * horizontalSpeed;
            double vz = Math.sin(angle) * horizontalSpeed;

            world.spawnParticles(ParticleTypes.GLOW_SQUID_INK, burstPos.x, burstPos.y, burstPos.z, 0, vx, upwardSpeed, vz, 1.0d);
            world.spawnParticles(ParticleTypes.LAVA, burstPos.x, burstPos.y, burstPos.z, 0, vx * 0.9d, upwardSpeed * 0.8d, vz * 0.9d, 1.0d);
            world.spawnParticles(ParticleTypes.FLAME, burstPos.x, burstPos.y, burstPos.z, 0, vx * 1.05d, upwardSpeed * 0.7d, vz * 1.05d, 1.0d);
        }
    }

    @Unique
    private void autoenchants$destroyAirburstBlocks(World world, Vec3d burstPos, int level) {
        int radius = 2 + level;
        BlockPos center = BlockPos.ofFloored(burstPos.x, burstPos.y - 1.0d, burstPos.z);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz + dy * dy > radius * radius) {
                        continue;
                    }
                    BlockPos targetPos = center.add(dx, dy, dz);
                    BlockState state = world.getBlockState(targetPos);
                    if (state.isAir() || state.isOf(Blocks.BEDROCK) || state.getHardness(world, targetPos) < 0.0f) {
                        continue;
                    }
                    if (state.getHardness(world, targetPos) > 4.0f + level * 0.7f) {
                        continue;
                    }
                    if (world.random.nextFloat() > 0.55f + level * 0.08f) {
                        continue;
                    }
                    world.breakBlock(targetPos, false);
                }
            }
        }
    }

    @Unique
    private BlockHitResult autoenchants$findGroundAhead(TridentEntity self) {
        Vec3d start = self.getPos();
        Vec3d velocity = self.getVelocity();
        Vec3d forward = velocity.normalize().multiply(1.5d);
        Vec3d end = start.add(forward).add(0.0d, -7.0d, 0.0d);
        BlockHitResult hitResult = self.getWorld().raycast(new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                self
        ));
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos blockPos = hitResult.getBlockPos();
        BlockState blockState = self.getWorld().getBlockState(blockPos);
        if (!blockState.isSideSolidFullSquare(self.getWorld(), blockPos, Direction.UP)) {
            return null;
        }
        return hitResult;
    }

    @Unique
    private boolean autoenchants$hasLineOfSight(TridentEntity self, LivingEntity target) {
        World world = self.getWorld();
        Vec3d start = self.getPos();
        Vec3d end = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);

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
        World world = self.getWorld();
        if (autoenchants$lockedTarget != null) {
            Entity existing = ((ServerWorld) world).getEntity(autoenchants$lockedTarget);
            if (existing instanceof LivingEntity living && living.isAlive()) {
                if (autoenchants$hasLineOfSight(self, living)) {
                    return living;
                }
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
                    self.getPos(),
                    forward,
                    26.0d + level * 8.0d,
                    50.0d,
                    self.getOwner()
            );
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
            Vec3d to = candidate.getPos().add(0.0d, candidate.getHeight() * 0.5d, 0.0d).subtract(self.getPos());
            if (to.lengthSquared() < 1.0E-5d) {
                continue;
            }
            Vec3d dir = to.normalize();
            double alignment = forward.dotProduct(dir);
            if (alignment < 0.45d) {
                continue;
            }
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

                    if (random.nextFloat() > 0.15f + level * 0.1f) {
                        continue;
                    }

                    BlockPos pos = center.add(dx, dy, dz);
                    BlockPos above = pos.up();

                    if (world.getBlockState(above).isAir() && world.getBlockState(pos).isSideSolidFullSquare(world, pos, Direction.UP)) {
                        world.setBlockState(above, Blocks.FIRE.getDefaultState());
                    }
                }
            }
        }
    }
}
