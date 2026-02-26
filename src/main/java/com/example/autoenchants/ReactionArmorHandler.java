package com.example.autoenchants;

import com.example.autoenchants.mixin.AbstractHorseEntityAccessor;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.LlamaSpitEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.DragonFireballEntity;
import net.minecraft.entity.projectile.FireballEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.entity.projectile.thrown.EggEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.ExperienceBottleEntity;
import net.minecraft.entity.projectile.thrown.PotionEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ItemStackParticleEffect;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ReactionArmorHandler {
    private static final double PROJECTILE_TRIGGER_RANGE = 2.0d;
    private static final double CONE_HALF_ANGLE_DEGREES = 45.0d;
    private static final double PROJECTILE_COUNTER_RANGE = 4.5d;
    private static final double MELEE_COUNTER_RANGE = 3.8d;
    private static final String ZERO_CD_NBT_KEY = "autoenchants:reaction_armor_zero_cd";
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> KNOCKED_LAVA_UNTIL = new HashMap<>();

    private ReactionArmorHandler() {
    }

    public static void tick(MinecraftServer server) {
        long now = server.getTicks();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            tryAutoTriggerFor(player, now);
            if (player.getVehicle() instanceof AbstractHorseEntity horse) {
                tryAutoTriggerFor(horse, now);
            }
        }
        tickKnockedLava(server, now);
    }

    public static void onDamaged(LivingEntity victim, DamageSource source) {
        if (victim.getWorld().isClient()) {
            return;
        }

        int level = getReactionArmorLevelForEntity(victim);
        LivingEntity armorWearer = victim;

        // If victim has no reaction armor but is riding a horse with one, delegate to the horse
        if (level <= 0 && victim.getVehicle() instanceof AbstractHorseEntity horse) {
            int horseLevel = getReactionArmorLevelForEntity(horse);
            if (horseLevel > 0) {
                level = horseLevel;
                armorWearer = horse;
            }
        }
        // If victim is a horse with no reaction armor, check if a rider has it via the horse armor
        // (already covered above since horse armor is on the horse entity)
        // Also: if a horse is hit and has a rider, still trigger from the horse
        if (level <= 0 && victim instanceof AbstractHorseEntity horse) {
            int horseLevel = getHorseArmorReactionLevel(horse);
            if (horseLevel > 0) {
                level = horseLevel;
                armorWearer = horse;
            }
        }

        if (level <= 0) {
            return;
        }
        long now = ((ServerWorld) armorWearer.getWorld()).getServer().getTicks();
        if (!isOffCooldown(armorWearer, now)) {
            return;
        }

        boolean explosive = source.isIn(DamageTypeTags.IS_EXPLOSION);
        if (!explosive && autoenchants$isIgnoredTriggerSource(source)) {
            return;
        }
        Vec3d direction;
        if (explosive) {
            Vec3d sourcePos = source.getPosition();
            if (sourcePos != null) {
                direction = armorWearer.getPos().subtract(sourcePos);
            } else if (source.getAttacker() != null) {
                direction = armorWearer.getPos().subtract(source.getAttacker().getPos());
            } else {
                direction = armorWearer.getRotationVec(1.0f);
            }
        } else {
            Entity attackerEntity = source.getAttacker();
            if (!(attackerEntity instanceof LivingEntity livingAttacker) || !isThreatToVictim(livingAttacker, armorWearer)) {
                return;
            }
            if (source.getSource() instanceof ProjectileEntity) {
                return;
            }
            direction = livingAttacker.getPos().subtract(armorWearer.getPos());
        }
        if (direction.lengthSquared() < 1.0E-6d) {
            direction = armorWearer.getRotationVec(1.0f);
        }
        if (direction.lengthSquared() < 1.0E-6d) {
            return;
        }
        direction = direction.normalize();
        triggerDefense(armorWearer, level, direction, MELEE_COUNTER_RANGE, true);
        startCooldown(armorWearer, level, now);
    }

    /**
     * Gets the reaction armor enchantment level for an entity, checking both standard
     * equipment slots and horse armor inventory.
     */
    private static int getReactionArmorLevelForEntity(LivingEntity entity) {
        int level = EnchantmentHelper.getEquipmentLevel(AutoEnchantsMod.REACTION_ARMOR, entity);
        if (level > 0) {
            return level;
        }
        if (entity instanceof AbstractHorseEntity horse) {
            return getHorseArmorReactionLevel(horse);
        }
        return 0;
    }

    /**
     * Gets the reaction armor level from a horse's armor slot (inventory index 1).
     */
    private static int getHorseArmorReactionLevel(AbstractHorseEntity horse) {
        SimpleInventory inv = ((AbstractHorseEntityAccessor) horse).autoenchants$getItems();
        if (inv.size() < 2) {
            return 0;
        }
        ItemStack armorStack = inv.getStack(1);
        if (armorStack.isEmpty()) {
            return 0;
        }
        return EnchantmentHelper.getLevel(AutoEnchantsMod.REACTION_ARMOR, armorStack);
    }

    private static ProjectileEntity findIncomingProjectile(LivingEntity victim, double range) {
        World world = victim.getWorld();
        Box box = victim.getBoundingBox().expand(range);
        List<ProjectileEntity> projectiles = world.getEntitiesByClass(
                ProjectileEntity.class,
                box,
                projectile -> projectile.isAlive() && !projectile.isRemoved() && !autoenchants$isIgnoredProjectile(projectile)
        );
        ProjectileEntity best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        Vec3d victimPos = victim.getPos().add(0.0d, victim.getStandingEyeHeight() * 0.7d, 0.0d);
        for (ProjectileEntity projectile : projectiles) {
            Entity owner = projectile.getOwner();
            boolean hostileOwner = owner instanceof LivingEntity livingOwner && isThreatToVictim(livingOwner, victim);
            boolean specialHostileProjectile = autoenchants$isSpecialHostileProjectile(projectile);
            if (!hostileOwner && !specialHostileProjectile) {
                continue;
            }
            Vec3d toVictim = victimPos.subtract(projectile.getPos());
            if (toVictim.lengthSquared() < 1.0E-6d) {
                continue;
            }
            Vec3d velocity = projectile.getVelocity();
            if (velocity.lengthSquared() < 1.0E-6d) {
                continue;
            }
            double incoming = velocity.normalize().dotProduct(toVictim.normalize());
            if (incoming < 0.35d) {
                continue;
            }
            double distanceSq = toVictim.lengthSquared();
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = projectile;
            }
        }
        return best;
    }

    private static FireworkRocketEntity findIncomingFirework(LivingEntity victim, double range) {
        World world = victim.getWorld();
        Box box = victim.getBoundingBox().expand(range);
        List<FireworkRocketEntity> fireworks = world.getEntitiesByClass(
                FireworkRocketEntity.class,
                box,
                firework -> firework.isAlive() && !firework.isRemoved()
        );
        FireworkRocketEntity best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        Vec3d victimPos = victim.getPos().add(0.0d, victim.getStandingEyeHeight() * 0.7d, 0.0d);
        for (FireworkRocketEntity firework : fireworks) {
            Entity owner = firework.getOwner();
            if (!(owner instanceof LivingEntity livingOwner) || !isThreatToVictim(livingOwner, victim)) {
                continue;
            }
            Vec3d toVictim = victimPos.subtract(firework.getPos());
            if (toVictim.lengthSquared() < 1.0E-6d) {
                continue;
            }
            Vec3d velocity = firework.getVelocity();
            if (velocity.lengthSquared() < 1.0E-6d) {
                continue;
            }
            double incoming = velocity.normalize().dotProduct(toVictim.normalize());
            if (incoming < 0.35d) {
                continue;
            }
            double distanceSq = toVictim.lengthSquared();
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = firework;
            }
        }
        return best;
    }

    private static void triggerDefense(LivingEntity wearer, int level, Vec3d sourceDirection, double range, boolean meleeMode) {
        if (!(wearer.getWorld() instanceof ServerWorld world)) {
            return;
        }
        Vec3d dir = sourceDirection.normalize();
        Vec3d origin = wearer.getPos().add(0.0d, wearer.getStandingEyeHeight() * 0.7d, 0.0d);

        int spitCount = 8 + level * 2;
        for (int i = 0; i < spitCount; i++) {
            Vec3d sprayDir = randomConeDirection(world, dir, CONE_HALF_ANGLE_DEGREES);
            LlamaSpitEntity spit = EntityType.LLAMA_SPIT.create(world);
            if (spit == null) {
                continue;
            }
            spit.refreshPositionAndAngles(origin.x, origin.y, origin.z, wearer.getYaw(), wearer.getPitch());
            spit.setVelocity(sprayDir.x, sprayDir.y, sprayDir.z, 1.35f + 0.12f * level, 0.0f);
            world.spawnEntity(spit);
        }
        world.spawnParticles(ParticleTypes.GLOW_SQUID_INK, origin.x, origin.y, origin.z, 28 + level * 4, 0.45d, 0.35d, 0.45d, 0.02d);
        world.spawnParticles(ParticleTypes.LANDING_HONEY, origin.x, origin.y, origin.z, 14 + level * 2, 0.55d, 0.35d, 0.55d, 0.01d);
        world.spawnParticles(ParticleTypes.LAVA, origin.x, origin.y, origin.z, 10 + level, 0.42d, 0.25d, 0.42d, 0.01d);

        // Collect entities that should be excluded from splash damage (wearer + rider/mount)
        float damage = meleeMode ? (3.0f + level) : (2.5f + 0.8f * level);
        List<LivingEntity> affected = world.getEntitiesByClass(
                LivingEntity.class,
                wearer.getBoundingBox().expand(range),
                target -> target.isAlive() && target != wearer && !isRiderOrMount(wearer, target)
        );
        for (LivingEntity target : affected) {
            Vec3d toTarget = target.getPos().add(0.0d, target.getStandingEyeHeight() * 0.5d, 0.0d).subtract(origin);
            if (!isInCone(toTarget, dir, CONE_HALF_ANGLE_DEGREES, range)) {
                continue;
            }
            target.damage(wearer.getDamageSources().mobAttack(wearer), damage);
            Vec3d push = toTarget.normalize();
            double horizontal = 0.7d + 0.12d * level;
            target.addVelocity(push.x * horizontal, 0.22d + 0.05d * level, push.z * horizontal);
            target.velocityModified = true;
            long now = world.getServer().getTicks();
            KNOCKED_LAVA_UNTIL.put(target.getUuid(), now + 30L);
            if (meleeMode) {
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 20 + 5 * level, 0, false, true, true));
            }
        }

        wearer.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, 20 + 2 * level, 0, false, true, true));
        wearer.addVelocity(-dir.x * 0.18d, 0.08d, -dir.z * 0.18d);
        wearer.velocityModified = true;
        damageReactionArmor(wearer);
    }

    /**
     * Returns true if target is the rider of wearer or the mount of wearer.
     */
    private static boolean isRiderOrMount(LivingEntity wearer, LivingEntity target) {
        if (wearer.getVehicle() == target) {
            return true;
        }
        if (target.getVehicle() == wearer) {
            return true;
        }
        return false;
    }

    private static boolean isOffCooldown(LivingEntity entity, long nowTicks) {
        StatusEffectInstance cooldown = entity.getStatusEffect(AutoEnchantsMod.REACTION_ARMOR_COOLDOWN);
        if (cooldown != null && cooldown.getDuration() > 0) {
            COOLDOWN_UNTIL.put(entity.getUuid(), nowTicks + cooldown.getDuration());
            return false;
        }
        COOLDOWN_UNTIL.remove(entity.getUuid());
        return true;
    }

    private static void startCooldown(LivingEntity entity, int level, long nowTicks) {
        if (isZeroCooldownDebug(entity)) {
            COOLDOWN_UNTIL.remove(entity.getUuid());
            entity.removeStatusEffect(AutoEnchantsMod.REACTION_ARMOR_COOLDOWN);
            return;
        }
        int cooldownTicks = Math.max(40, 300 - (level - 1) * 40);
        COOLDOWN_UNTIL.put(entity.getUuid(), nowTicks + cooldownTicks);
        entity.addStatusEffect(new StatusEffectInstance(AutoEnchantsMod.REACTION_ARMOR_COOLDOWN, cooldownTicks, 0, false, false, true));
    }

    private static boolean isInCone(Vec3d toTarget, Vec3d forward, double halfAngleDegrees, double maxDistance) {
        if (toTarget.lengthSquared() < 1.0E-6d) {
            return false;
        }
        double distance = toTarget.length();
        if (distance > maxDistance) {
            return false;
        }
        double cosThreshold = Math.cos(Math.toRadians(halfAngleDegrees));
        return toTarget.normalize().dotProduct(forward) >= cosThreshold;
    }

    private static Vec3d randomConeDirection(ServerWorld world, Vec3d forward, double halfAngleDegrees) {
        double cosThreshold = Math.cos(Math.toRadians(halfAngleDegrees));
        for (int i = 0; i < 12; i++) {
            Vec3d candidate = new Vec3d(
                    world.random.nextDouble() * 2.0d - 1.0d,
                    world.random.nextDouble() * 0.9d - 0.35d,
                    world.random.nextDouble() * 2.0d - 1.0d
            );
            if (candidate.lengthSquared() < 1.0E-6d) {
                continue;
            }
            candidate = candidate.normalize();
            if (candidate.dotProduct(forward) >= cosThreshold) {
                return candidate;
            }
        }
        return forward;
    }

    private static boolean autoenchants$isIgnoredProjectile(ProjectileEntity projectile) {
        return projectile instanceof PotionEntity
                || projectile instanceof SnowballEntity
                || projectile instanceof ExperienceBottleEntity
                || projectile instanceof EggEntity
                || projectile instanceof EnderPearlEntity;
    }

    private static boolean autoenchants$isSpecialHostileProjectile(ProjectileEntity projectile) {
        return projectile instanceof ShulkerBulletEntity
                || projectile instanceof FireballEntity
                || projectile instanceof WitherSkullEntity
                || projectile instanceof DragonFireballEntity;
    }

    private static boolean autoenchants$isIgnoredTriggerSource(DamageSource source) {
        Entity direct = source.getSource();
        return direct instanceof PotionEntity
                || direct instanceof SnowballEntity
                || direct instanceof ExperienceBottleEntity
                || direct instanceof EggEntity
                || direct instanceof EnderPearlEntity
                || direct instanceof EvokerFangsEntity
                || direct instanceof ItemEntity
                || direct instanceof TntEntity;
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
        // Also consider threats targeting the rider or mount of the victim
        if (victim instanceof AbstractHorseEntity && attacker instanceof MobEntity mob) {
            for (Entity passenger : victim.getPassengerList()) {
                if (mob.getTarget() == passenger) {
                    return true;
                }
            }
        }
        if (victim.getVehicle() instanceof AbstractHorseEntity horse && attacker instanceof MobEntity mob) {
            if (mob.getTarget() == horse) {
                return true;
            }
        }
        return false;
    }

    private static void damageReactionArmor(LivingEntity wearer) {
        EquippedReactionArmor equipped = getReactionArmorPiece(wearer);
        if (equipped != null) {
            equipped.stack.damage(1, wearer, entity -> entity.sendEquipmentBreakStatus(equipped.slot));
        }
    }

    private static Vec3d getPriorityThreatDirection(LivingEntity victim, ProjectileEntity projectile) {
        Entity owner = projectile.getOwner();
        if (owner instanceof LivingEntity) {
            Vec3d toOwner = owner.getPos().subtract(victim.getPos());
            if (toOwner.lengthSquared() >= 1.0E-6d) {
                return toOwner.normalize();
            }
        }
        Vec3d toProjectile = projectile.getPos().subtract(victim.getPos());
        if (toProjectile.lengthSquared() >= 1.0E-6d) {
            return toProjectile.normalize();
        }
        Vec3d incoming = projectile.getVelocity().multiply(-1.0d);
        if (incoming.lengthSquared() >= 1.0E-6d) {
            return incoming.normalize();
        }
        return Vec3d.ZERO;
    }

    private static boolean isZeroCooldownDebug(LivingEntity entity) {
        EquippedReactionArmor equipped = getReactionArmorPiece(entity);
        if (equipped == null || !equipped.stack.hasNbt()) {
            return false;
        }
        NbtCompound nbt = equipped.stack.getNbt();
        return nbt != null && nbt.getBoolean(ZERO_CD_NBT_KEY);
    }

    private static EquippedReactionArmor getReactionArmorPiece(LivingEntity entity) {
        // Check standard equipment slots first
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = entity.getEquippedStack(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (EnchantmentHelper.getLevel(AutoEnchantsMod.REACTION_ARMOR, stack) <= 0) {
                continue;
            }
            return new EquippedReactionArmor(slot, stack);
        }
        // Check horse armor inventory
        if (entity instanceof AbstractHorseEntity horse) {
            SimpleInventory inv = ((AbstractHorseEntityAccessor) horse).autoenchants$getItems();
            if (inv.size() >= 2) {
                ItemStack armorStack = inv.getStack(1);
                if (!armorStack.isEmpty() && EnchantmentHelper.getLevel(AutoEnchantsMod.REACTION_ARMOR, armorStack) > 0) {
                    // Use CHEST as a proxy slot for horse armor
                    return new EquippedReactionArmor(EquipmentSlot.CHEST, armorStack);
                }
            }
        }
        return null;
    }

    private record EquippedReactionArmor(EquipmentSlot slot, ItemStack stack) {
    }

    public static String getZeroCooldownNbtKey() {
        return ZERO_CD_NBT_KEY;
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
        return new ItemStack(Items.ARROW);
    }

    private static void tickKnockedLava(MinecraftServer server, long nowTicks) {
        if (KNOCKED_LAVA_UNTIL.isEmpty()) {
            return;
        }
        KNOCKED_LAVA_UNTIL.entrySet().removeIf(entry -> {
            if (entry.getValue() <= nowTicks) {
                return true;
            }
            Entity entity = null;
            for (ServerWorld world : server.getWorlds()) {
                entity = world.getEntity(entry.getKey());
                if (entity != null) {
                    break;
                }
            }
            if (!(entity instanceof LivingEntity living) || !living.isAlive()) {
                return true;
            }
            ServerWorld world = (ServerWorld) living.getWorld();
            world.spawnParticles(ParticleTypes.LAVA, living.getX(), living.getBodyY(0.5d), living.getZ(), 3, 0.22d, 0.18d, 0.22d, 0.0d);
            return false;
        });
    }

    private static void tryAutoTriggerFor(LivingEntity wearer, long nowTicks) {
        if (!wearer.isAlive() || (wearer instanceof ServerPlayerEntity player && player.isSpectator())) {
            return;
        }
        int level = getReactionArmorLevelForEntity(wearer);
        if (level <= 0 || !isOffCooldown(wearer, nowTicks)) {
            return;
        }

        ProjectileEntity incomingProjectile = findIncomingProjectile(wearer, PROJECTILE_TRIGGER_RANGE);
        if (incomingProjectile != null) {
            Vec3d direction = getPriorityThreatDirection(wearer, incomingProjectile);
            if (direction.lengthSquared() < 1.0E-6d) {
                return;
            }
            triggerDefense(wearer, level, direction.normalize(), PROJECTILE_COUNTER_RANGE, false);
            spawnProjectileShatterParticles((ServerWorld) wearer.getWorld(), incomingProjectile);
            incomingProjectile.discard();
            startCooldown(wearer, level, nowTicks);
            return;
        }

        FireworkRocketEntity incomingFirework = findIncomingFirework(wearer, PROJECTILE_TRIGGER_RANGE);
        if (incomingFirework != null) {
            Vec3d direction = getPriorityThreatDirectionForEntity(wearer, incomingFirework);
            if (direction.lengthSquared() < 1.0E-6d) {
                return;
            }
            triggerDefense(wearer, level, direction.normalize(), PROJECTILE_COUNTER_RANGE, false);
            ((ServerWorld) wearer.getWorld()).spawnParticles(
                    new ItemStackParticleEffect(ParticleTypes.ITEM, new ItemStack(Items.FIREWORK_ROCKET)),
                    incomingFirework.getX(), incomingFirework.getY(), incomingFirework.getZ(),
                    18, 0.12d, 0.12d, 0.12d, 0.02d
            );
            incomingFirework.discard();
            startCooldown(wearer, level, nowTicks);
        }
    }

    private static Vec3d getPriorityThreatDirectionForEntity(LivingEntity victim, Entity threat) {
        if (threat instanceof ProjectileEntity projectile && projectile.getOwner() instanceof LivingEntity owner) {
            Vec3d toOwner = owner.getPos().subtract(victim.getPos());
            if (toOwner.lengthSquared() >= 1.0E-6d) {
                return toOwner.normalize();
            }
        } else if (threat instanceof FireworkRocketEntity firework && firework.getOwner() instanceof LivingEntity owner) {
            Vec3d toOwner = owner.getPos().subtract(victim.getPos());
            if (toOwner.lengthSquared() >= 1.0E-6d) {
                return toOwner.normalize();
            }
        }
        Vec3d toThreat = threat.getPos().subtract(victim.getPos());
        if (toThreat.lengthSquared() >= 1.0E-6d) {
            return toThreat.normalize();
        }
        Vec3d incoming = threat.getVelocity().multiply(-1.0d);
        if (incoming.lengthSquared() >= 1.0E-6d) {
            return incoming.normalize();
        }
        return Vec3d.ZERO;
    }
}
