package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.GhastEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.GlowSquidEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.EggEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SquidIronFistHandler {
    private static final double DETECTION_RANGE = 10.0d;
    private static final int THREAT_SCAN_INTERVAL_TICKS = 2;
    private static final double INTERCEPT_DISTANCE = 1.2d;
    private static final double INTERCEPT_SPEED = 1.7d;
    private static final int INTERCEPT_TTL = 40;
    private static final String ZERO_CD_NBT_KEY = ReactionArmorHandler.getZeroCooldownNbtKey();
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
    private static final Map<UUID, InterceptorState> ACTIVE_INTERCEPTORS = new HashMap<>();
    private static final Map<UUID, Long> NEXT_THREAT_SCAN_AT = new HashMap<>();
    private static EntityAttribute SQUID_SCALE_ATTRIBUTE;
    private static boolean SCALE_ATTR_RESOLVED = false;

    private SquidIronFistHandler() {
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        tickInterceptors(server, now);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!player.isAlive() || player.isSpectator()) {
                UUID playerId = player.getUuid();
                COOLDOWN_UNTIL.remove(playerId);
                NEXT_THREAT_SCAN_AT.remove(playerId);
                continue;
            }
            int level = EnchantmentHelper.getEquipmentLevel(AutoEnchantsMod.SQUID_IRON_FIST, player);
            if (level <= 0 || !isOffCooldown(player, now)) {
                continue;
            }
            UUID playerId = player.getUuid();
            Long nextScanAt = NEXT_THREAT_SCAN_AT.get(playerId);
            if (nextScanAt != null && now < nextScanAt) {
                continue;
            }
            NEXT_THREAT_SCAN_AT.put(playerId, now + THREAT_SCAN_INTERVAL_TICKS);

            Entity threat = findIncomingThreat(player, DETECTION_RANGE);
            if (threat == null) {
                continue;
            }
            launchInterceptor(player, threat, level, now);
            startCooldown(player, level, now);
        }
    }

    private static Entity findIncomingThreat(ServerPlayerEntity player, double range) {
        Entity incomingProjectile = findIncomingProjectileThreat(player, range);
        if (incomingProjectile != null) {
            return incomingProjectile;
        }
        return findIncomingFlyingThreat(player, range);
    }

    private static Entity findIncomingProjectileThreat(ServerPlayerEntity player, double range) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getPos().add(0.0d, player.getStandingEyeHeight() * 0.7d, 0.0d);
        Box box = player.getBoundingBox().expand(range);
        List<ProjectileEntity> projectiles = world.getEntitiesByClass(
                ProjectileEntity.class,
                box,
                projectile -> projectile.isAlive() && !projectile.isRemoved() && !isIgnoredProjectile(projectile)
        );
        Entity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (ProjectileEntity entity : projectiles) {
            if (!isApsThreat(entity, player)) {
                continue;
            }
            Vec3d toPlayer = eye.subtract(entity.getPos());
            double distance = toPlayer.length();
            if (distance > range || distance < 1.0E-6d) {
                continue;
            }
            Vec3d dirToPlayer = toPlayer.normalize();
            Vec3d movement = entity.getVelocity();
            if (movement.lengthSquared() > 1.0E-6d) {
                double incoming = movement.normalize().dotProduct(dirToPlayer);
                if (incoming < 0.12d) {
                    continue;
                }
            }
            double score = (range - distance) * 12.0d;
            score += 30.0d;
            if (score > bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private static Entity findIncomingFlyingThreat(ServerPlayerEntity player, double range) {
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getPos().add(0.0d, player.getStandingEyeHeight() * 0.7d, 0.0d);
        Box box = player.getBoundingBox().expand(range);
        List<MobEntity> entities = world.getEntitiesByClass(
                MobEntity.class,
                box,
                living -> living.isAlive() && !living.isRemoved()
        );
        MobEntity best = null;
        double bestScore = -Double.MAX_VALUE;
        for (MobEntity entity : entities) {
            if (!isApsThreat(entity, player)) {
                continue;
            }
            Vec3d toPlayer = eye.subtract(entity.getPos());
            double distance = toPlayer.length();
            if (distance > range || distance < 1.0E-6d) {
                continue;
            }
            double score = (range - distance) * 12.0d;
            if (score > bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private static boolean isApsThreat(Entity entity, ServerPlayerEntity player) {
        if (entity == player) {
            return false;
        }
        if (entity instanceof ProjectileEntity projectile) {
            if (isIgnoredProjectile(projectile)) {
                return false;
            }
            if (isSpecialHostileProjectile(projectile)) {
                return true;
            }
            Entity owner = projectile.getOwner();
            if (owner instanceof LivingEntity livingOwner) {
                return isThreatToVictim(livingOwner, player);
            }
            return false;
        }
        if (entity instanceof FireworkRocketEntity firework) {
            Entity owner = firework.getOwner();
            return owner instanceof LivingEntity livingOwner && isThreatToVictim(livingOwner, player);
        }
        if (!(entity instanceof LivingEntity living)) {
            return false;
        }
        if (!isThreatToVictim(living, player)) {
            return false;
        }
        if (living instanceof EnderDragonEntity || living instanceof WitherEntity) {
            return false;
        }
        return living instanceof FlyingEntity
                || living instanceof BeeEntity
                || living instanceof PhantomEntity
                || living instanceof GhastEntity
                || living instanceof VexEntity
                || living instanceof BlazeEntity;
    }

    private static void launchInterceptor(ServerPlayerEntity owner, Entity target, int level, long nowTicks) {
        ServerWorld world = owner.getServerWorld();
        GlowSquidEntity squid = EntityType.GLOW_SQUID.create(world);
        if (squid == null) {
            return;
        }
        Vec3d launchDir = target.getPos().subtract(owner.getPos());
        if (launchDir.lengthSquared() < 1.0E-6d) {
            launchDir = owner.getRotationVec(1.0f);
        }
        launchDir = launchDir.normalize();
        Vec3d spawnPos = owner.getPos().add(0.0d, owner.getStandingEyeHeight() * 0.6d, 0.0d).add(launchDir.multiply(0.6d));

        squid.refreshPositionAndAngles(spawnPos.x, spawnPos.y, spawnPos.z, owner.getYaw(), owner.getPitch());
        squid.setNoGravity(true);
        squid.setAiDisabled(true);
        squid.setSilent(true);
        squid.setInvulnerable(true);
        squid.setVelocity(launchDir.multiply(INTERCEPT_SPEED));
        applySquidScale(squid, 0.2d);
        orientSquidTowardsDirection(squid, launchDir);
        world.spawnEntity(squid);

        world.spawnParticles(ParticleTypes.LARGE_SMOKE, spawnPos.x, spawnPos.y, spawnPos.z, 4, 0.06d, 0.06d, 0.06d, 0.02d);
        ACTIVE_INTERCEPTORS.put(squid.getUuid(), new InterceptorState(
                owner.getUuid(),
                target.getUuid(),
                world.getRegistryKey(),
                level,
                nowTicks + INTERCEPT_TTL
        ));
    }

    private static void tickInterceptors(MinecraftServer server, long nowTicks) {
        if (ACTIVE_INTERCEPTORS.isEmpty()) {
            return;
        }
        ACTIVE_INTERCEPTORS.entrySet().removeIf(entry -> {
            UUID squidId = entry.getKey();
            InterceptorState state = entry.getValue();
            ServerWorld world = server.getWorld(state.worldKey());
            if (world == null || nowTicks > state.expireTick()) {
                return true;
            }
            Entity squidEntity = world.getEntity(squidId);
            Entity target = world.getEntity(state.targetId());
            Entity ownerEntity = world.getEntity(state.ownerId());
            if (!(squidEntity instanceof GlowSquidEntity squid) || target == null || !target.isAlive()) {
                if (squidEntity != null) {
                    squidEntity.discard();
                }
                return true;
            }

            Vec3d toTarget = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d).subtract(squid.getPos());
            if (toTarget.lengthSquared() < INTERCEPT_DISTANCE * INTERCEPT_DISTANCE) {
                explodeOnIntercept(world, squid, target, ownerEntity, state.level());
                squid.discard();
                return true;
            }
            Vec3d direction = toTarget.normalize();
            squid.setVelocity(direction.multiply(INTERCEPT_SPEED));
            orientSquidTowardsDirection(squid, direction);
            squid.velocityModified = true;
            world.spawnParticles(ParticleTypes.GLOW, squid.getX(), squid.getBodyY(0.5d), squid.getZ(), 3, 0.03d, 0.03d, 0.03d, 0.0d);
            return false;
        });
    }

    private static void explodeOnIntercept(ServerWorld world, GlowSquidEntity squid, Entity target, Entity ownerEntity, int level) {
        Vec3d hitPos = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);
        world.createExplosion(ownerEntity, hitPos.x, hitPos.y, hitPos.z, 1.0f, false, World.ExplosionSourceType.MOB);
        world.spawnParticles(ParticleTypes.EXPLOSION, hitPos.x, hitPos.y, hitPos.z, 2, 0.15d, 0.15d, 0.15d, 0.01d);

        Vec3d forward = target.getPos().subtract(squid.getPos());
        if (forward.lengthSquared() < 1.0E-6d) {
            forward = squid.getVelocity();
        }
        if (forward.lengthSquared() < 1.0E-6d) {
            forward = new Vec3d(0.0d, 0.0d, 1.0d);
        }
        forward = forward.normalize();

        for (int i = 0; i < 16; i++) {
            Vec3d p = hitPos.add(forward.multiply(0.2d * i));
            world.spawnParticles(ParticleTypes.GLOW_SQUID_INK, p.x, p.y + 0.05d, p.z, 2, 0.04d, 0.04d, 0.04d, 0.01d);
        }
        for (int i = 0; i < 16; i++) {
            double angle = i * (Math.PI * 2.0d / 16.0d);
            double rx = Math.cos(angle) * 0.9d;
            double rz = Math.sin(angle) * 0.9d;
            world.spawnParticles(ParticleTypes.SQUID_INK, hitPos.x + rx, hitPos.y + 0.08d, hitPos.z + rz, 1, 0.01d, 0.01d, 0.01d, 0.0d);
        }
        applyForwardSplashEffects(world, ownerEntity, target, hitPos, forward, level);

        if (target instanceof LivingEntity living) {
            float damage = 6.0f + level;
            if (ownerEntity instanceof LivingEntity ownerLiving) {
                living.damage(ownerLiving.getDamageSources().mobAttack(ownerLiving), damage);
            } else {
                living.damage(world.getDamageSources().generic(), damage);
            }
            Vec3d knock = forward.multiply(1.1d);
            living.addVelocity(knock.x, 0.35d + 0.08d * level, knock.z);
            living.velocityModified = true;
            living.setOnFireFor(3 + level);
            return;
        }

        if (target instanceof ProjectileEntity projectile) {
            spawnProjectileShatterParticles(world, projectile);
        } else {
            world.spawnParticles(new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.INK_SAC)),
                    target.getX(), target.getY(), target.getZ(), 12, 0.12d, 0.12d, 0.12d, 0.01d);
        }
        target.discard();
    }

    private static boolean isOffCooldown(LivingEntity entity, long nowTicks) {
        StatusEffectInstance cooldown = entity.getStatusEffect(AutoEnchantsMod.SQUID_IRON_FIST_COOLDOWN);
        if (cooldown != null && cooldown.getDuration() > 0) {
            COOLDOWN_UNTIL.put(entity.getUuid(), nowTicks + cooldown.getDuration());
            return false;
        }
        COOLDOWN_UNTIL.remove(entity.getUuid());
        return true;
    }

    private static void startCooldown(ServerPlayerEntity player, int level, long nowTicks) {
        if (isZeroCooldownDebug(player)) {
            COOLDOWN_UNTIL.remove(player.getUuid());
            player.removeStatusEffect(AutoEnchantsMod.SQUID_IRON_FIST_COOLDOWN);
            return;
        }
        int cooldownTicks = Math.max(40, 300 - (level - 1) * 40);
        COOLDOWN_UNTIL.put(player.getUuid(), nowTicks + cooldownTicks);
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                AutoEnchantsMod.SQUID_IRON_FIST_COOLDOWN, cooldownTicks, 0, false, false, true
        ));
    }

    private static boolean isZeroCooldownDebug(ServerPlayerEntity player) {
        ItemStack helmet = player.getEquippedStack(EquipmentSlot.HEAD);
        if (helmet.isEmpty() || EnchantmentHelper.getLevel(AutoEnchantsMod.SQUID_IRON_FIST, helmet) <= 0) {
            return false;
        }
        NbtCompound nbt = helmet.getNbt();
        return nbt != null && nbt.getBoolean(ZERO_CD_NBT_KEY);
    }

    private static boolean isThreatToVictim(LivingEntity attacker, LivingEntity victim) {
        if (attacker instanceof EnderDragonEntity || attacker instanceof WitherEntity) {
            return true;
        }
        if (attacker instanceof HostileEntity) {
            return true;
        }
        if (attacker instanceof MobEntity mob && mob.getTarget() == victim) {
            return true;
        }
        return false;
    }

    private static boolean isSpecialHostileProjectile(ProjectileEntity projectile) {
        return projectile instanceof ShulkerBulletEntity
                || projectile instanceof FireballEntity
                || projectile instanceof WitherSkullEntity
                || projectile instanceof DragonFireballEntity;
    }

    private static boolean isIgnoredProjectile(ProjectileEntity projectile) {
        return projectile instanceof PotionEntity
                || projectile instanceof SnowballEntity
                || projectile instanceof ExperienceBottleEntity
                || projectile instanceof EggEntity
                || projectile instanceof EnderPearlEntity;
    }

    private static void spawnProjectileShatterParticles(ServerWorld world, ProjectileEntity projectile) {
        ItemStack shardStack = getProjectileShardStack(projectile);
        ItemStackParticleEffect effect = new ItemStackParticleEffect(ParticleTypes.ITEM, shardStack);
        world.spawnParticles(effect, projectile.getX(), projectile.getY(), projectile.getZ(), 18, 0.12d, 0.12d, 0.12d, 0.02d);
    }

    private static ItemStack getProjectileShardStack(ProjectileEntity projectile) {
        if (projectile instanceof SpectralArrowEntity) {
            return new ItemStack(Items.SPECTRAL_ARROW);
        }
        if (projectile instanceof ArrowEntity) {
            return new ItemStack(Items.ARROW);
        }
        if (projectile instanceof TridentEntity) {
            return new ItemStack(Items.TRIDENT);
        }
        if (projectile instanceof FireballEntity) {
            return new ItemStack(Items.FIRE_CHARGE);
        }
        if (projectile instanceof DragonFireballEntity) {
            return new ItemStack(Items.DRAGON_BREATH);
        }
        if (projectile instanceof WitherSkullEntity) {
            return new ItemStack(Items.WITHER_SKELETON_SKULL);
        }
        if (projectile instanceof ShulkerBulletEntity) {
            return new ItemStack(Items.POPPED_CHORUS_FRUIT);
        }
        return new ItemStack(Items.ARROW);
    }

    private record InterceptorState(
            UUID ownerId,
            UUID targetId,
            RegistryKey<World> worldKey,
            int level,
            long expireTick
    ) {
    }

    private static void applyForwardSplashEffects(ServerWorld world, Entity ownerEntity, Entity primaryTarget, Vec3d hitPos, Vec3d forward, int level) {
        double range = 3.0d;
        Box splashBox = new Box(hitPos.subtract(range, range, range), hitPos.add(range, range, range));
        List<LivingEntity> victims = world.getEntitiesByClass(
                LivingEntity.class,
                splashBox,
                living -> living.isAlive() && living != ownerEntity
        );
        for (LivingEntity victim : victims) {
            Vec3d toVictim = victim.getPos().add(0.0d, victim.getHeight() * 0.5d, 0.0d).subtract(hitPos);
            double distance = toVictim.length();
            if (distance > range || distance < 1.0E-6d) {
                continue;
            }
            Vec3d dir = toVictim.normalize();
            if (dir.dotProduct(forward) < 0.25d) {
                continue;
            }
            if (victim == primaryTarget) {
                continue;
            }
            float splashDamage = 2.0f + level * 0.8f;
            if (ownerEntity instanceof LivingEntity ownerLiving) {
                victim.damage(ownerLiving.getDamageSources().mobAttack(ownerLiving), splashDamage);
            } else {
                victim.damage(world.getDamageSources().generic(), splashDamage);
            }
            Vec3d knock = dir.multiply(0.8d + 0.1d * level);
            victim.addVelocity(knock.x, 0.2d + 0.05d * level, knock.z);
            victim.velocityModified = true;
            victim.setOnFireFor(2 + level);
        }

        for (int i = 1; i <= 6; i++) {
            Vec3d step = hitPos.add(forward.multiply(0.5d * i));
            BlockPos center = BlockPos.ofFloored(step);
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    BlockPos firePos = center.add(ox, 0, oz);
                    if (!world.getBlockState(firePos).isAir()) {
                        continue;
                    }
                    BlockPos below = firePos.down();
                    if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) {
                        continue;
                    }
                    if (world.random.nextFloat() < 0.35f) {
                        world.setBlockState(firePos, AbstractFireBlock.getState(world, firePos));
                    }
                }
            }
        }
    }

    private static void orientTowardsDirection(Entity entity, Vec3d direction) {
        Vec3d dir = direction.normalize();
        float yaw = (float) (MathHelper.atan2(dir.z, dir.x) * (180.0d / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)) * (180.0d / Math.PI)));
        entity.setYaw(yaw);
        entity.setPitch(pitch);
        entity.prevYaw = yaw;
        entity.prevPitch = pitch;
    }

    private static void orientSquidTowardsDirection(GlowSquidEntity squid, Vec3d direction) {
        Vec3d dir = direction.normalize();
        float yaw = (float) (MathHelper.atan2(dir.z, dir.x) * (180.0d / Math.PI)) - 90.0f;
        float pitch = (float) (-(MathHelper.atan2(dir.y, Math.sqrt(dir.x * dir.x + dir.z * dir.z)) * (180.0d / Math.PI)));
        // 鱿鱼模型的前向轴与常规生物不同，需要 -90 度补偿让头朝前
        squid.setYaw(yaw);
        squid.setPitch(pitch - 90.0f);
        squid.prevYaw = yaw;
        squid.prevPitch = pitch - 90.0f;
    }

    private static void applySquidScale(GlowSquidEntity squid, double scale) {
        EntityAttribute attribute = resolveScaleAttribute();
        if (attribute == null) {
            return;
        }
        EntityAttributeInstance instance = squid.getAttributeInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(scale);
        }
    }

    private static EntityAttribute resolveScaleAttribute() {
        if (SCALE_ATTR_RESOLVED) {
            return SQUID_SCALE_ATTRIBUTE;
        }
        SCALE_ATTR_RESOLVED = true;
        try {
            Field field = EntityAttributes.class.getField("GENERIC_SCALE");
            Object value = field.get(null);
            if (value instanceof EntityAttribute entityAttribute) {
                SQUID_SCALE_ATTRIBUTE = entityAttribute;
            }
        } catch (ReflectiveOperationException ignored) {
            SQUID_SCALE_ATTRIBUTE = null;
        }
        return SQUID_SCALE_ATTRIBUTE;
    }
}
