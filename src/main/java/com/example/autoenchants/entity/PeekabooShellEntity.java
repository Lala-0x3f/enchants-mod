package com.example.autoenchants.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.ai.pathing.PathNode;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.item.DyeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PeekabooShellEntity extends ShulkerEntity {
    private static final double TARGET_SEARCH_RANGE = 48.0d;
    private static final int SEARCH_INTERVAL_TICKS = 20;
    private static final int IDLE_PEEK_AMOUNT = 35;
    private static final int COMBAT_PEEK_AMOUNT = 100;
    private static final int MIN_SHOT_INTERVAL = 20;
    private static final int MAX_SHOT_INTERVAL = 110;
    private static final int NON_COMBAT_TELEPORT_COOLDOWN_MIN = 260;
    private static final int NON_COMBAT_TELEPORT_COOLDOWN_MAX = 420;
    private static final int PURR_INTERVAL_MIN = 80;
    private static final int PURR_INTERVAL_MAX = 180;
    private static final float CLOSED_DAMAGE_FACTOR = 0.35f;
    private static final int SOCIAL_INTERVAL_MIN = 30;
    private static final int SOCIAL_INTERVAL_MAX = 60;
    private static final float STACK_TRIGGER_CHANCE = 0.18f;
    private static final int MAX_STACK_SIZE = 8;
    private static final double MAX_ENGAGE_PATH_DISTANCE = TARGET_SEARCH_RANGE * 1.5d;
    private static final int OBSERVE_TICKS_MIN = 20;
    private static final int OBSERVE_TICKS_MAX = 45;
    private static final float FULL_HEALTH_EPSILON = 0.001f;
    private static final float[] HAPPY_ARPEGGIO_PITCHES = new float[]{0.63f, 0.75f, 0.84f, 1.0f, 1.26f};
    private static final int HAPPY_ARPEGGIO_INTERVAL_TICKS = 6;

    private int shotCooldownTicks = MIN_SHOT_INTERVAL;
    private int searchCooldownTicks = 5;
    private int dayTeleportCooldownTicks = NON_COMBAT_TELEPORT_COOLDOWN_MIN;
    private int purrCooldownTicks = 60;
    private int socialCooldownTicks = 20;
    private int topStepNudgeCooldownTicks = 0;
    private int happySpinTicks = 0;
    private int observeTicks = 0;
    private int happyArpeggioStep = 0;
    private boolean happyArpeggioAscending = true;

    public PeekabooShellEntity(EntityType<? extends PeekabooShellEntity> entityType, World world) {
        super(entityType, world);
        this.experiencePoints = 8;
        this.resetNonCombatTeleportCooldown();
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        
        // Remove default shulker shooting goal - we handle shooting in mobTick()
        this.goalSelector.clear(goal -> {
            String className = goal.getClass().getSimpleName();
            return className.contains("ShootBullet") || className.contains("Shoot");
        });
        
        this.targetSelector.add(1, new ActiveTargetGoal<>(this, RaiderEntity.class, true));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, CreeperEntity.class, true));
        this.targetSelector.add(3, new ActiveTargetGoal<>(this, HostileEntity.class, true, target -> !(target instanceof ShulkerEntity)));
    }

    @Override
    protected void mobTick() {
        super.mobTick();
        if (this.getWorld().isClient()) {
            return;
        }

        if (this.happySpinTicks > 0) {
            this.setBodyYaw(this.getBodyYaw() + 24.0f);
            this.setHeadYaw(this.getHeadYaw() + 24.0f);
            this.playHappyArpeggioTick();
            this.happySpinTicks--;
        }

        if (--this.searchCooldownTicks <= 0) {
            this.searchCooldownTicks = SEARCH_INTERVAL_TICKS;
            LivingEntity bestTarget = this.findPriorityTarget();
            this.setTarget(bestTarget);
        }

        LivingEntity target = this.getTarget();
        if (target != null && !this.isValidCombatTarget(target)) {
            this.setTarget(null);
            target = null;
        }

        if (target != null) {
            if (this.observeTicks > 0) {
                this.observeTicks--;
                this.setPeekAmountRaw(0);
                return;
            }
            this.setPeekAmountRaw(COMBAT_PEEK_AMOUNT);
            if (--this.shotCooldownTicks <= 0) {
                this.shotCooldownTicks = this.random.nextBetween(MIN_SHOT_INTERVAL, MAX_SHOT_INTERVAL);
                if (this.isTargetWithinEngagePathDistance(target)) {
                    this.fireSparkVolley(target);
                    this.observeTicks = this.random.nextBetween(OBSERVE_TICKS_MIN, OBSERVE_TICKS_MAX);
                    this.setPeekAmountRaw(0);
                } else {
                    this.setTarget(null);
                }
            }
            return;
        }

        this.setPeekAmountRaw(IDLE_PEEK_AMOUNT);
        if (--this.purrCooldownTicks <= 0) {
            this.purrCooldownTicks = this.random.nextBetween(PURR_INTERVAL_MIN, PURR_INTERVAL_MAX);
            this.playSound(SoundEvents.ENTITY_CAT_PURR, 0.7f, 0.9f + this.random.nextFloat() * 0.2f);
        }
        if (--this.socialCooldownTicks <= 0) {
            this.socialCooldownTicks = this.random.nextBetween(SOCIAL_INTERVAL_MIN, SOCIAL_INTERVAL_MAX);
            if (!this.tryScatterWhenPlayerNear()) {
                if (this.random.nextFloat() < STACK_TRIGGER_CHANCE) {
                    this.tryCuriousStacking();
                }
            }
        }
        this.tryTopStepNudge();
        if (--this.dayTeleportCooldownTicks <= 0) {
            this.resetNonCombatTeleportCooldown();
            this.tryNonCombatTeleportPreference();
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        Entity attacker = source.getSource();
        if (attacker instanceof ShulkerBulletEntity) {
            return false;
        }
        if (attacker instanceof PeekabooSparkEntity) {
            this.tryTeleportWithBehaviorSound(1.0f, 1.08f);
            return false;
        }
        if (this.getHealth() <= this.getMaxHealth() * 0.25f) {
            this.tryTeleportWithBehaviorSound(1.0f, 0.95f);
        }

        boolean shellClosed = this.dataTracker.get(PEEK_AMOUNT) <= 0;
        if (shellClosed) {
            if (attacker instanceof PersistentProjectileEntity projectile) {
                Vec3d reflected = projectile.getVelocity().multiply(-0.85d);
                projectile.setVelocity(reflected);
                projectile.velocityModified = true;
                this.playSound(SoundEvents.BLOCK_ANVIL_HIT, 0.8f, 1.8f);
                return false;
            }
            amount *= CLOSED_DAMAGE_FACTOR;
        }
        boolean damaged = super.damage(source, amount);
        if (damaged) {
            if (this.dataTracker.get(PEEK_AMOUNT) <= 0) {
                this.playSound(SoundEvents.ENTITY_SHULKER_HURT_CLOSED, 0.85f, 0.95f + this.random.nextFloat() * 0.1f);
            } else {
                this.playSound(SoundEvents.ENTITY_SHULKER_HURT, 0.85f, 0.95f + this.random.nextFloat() * 0.1f);
            }
        }
        return damaged;
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (stack.getItem() instanceof DyeItem dyeItem) {
            if (!this.getWorld().isClient()) {
                this.setVariant(Optional.of(dyeItem.getColor()));
                this.playSound(SoundEvents.ITEM_DYE_USE, 0.8f, 1.0f);
                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }
            }
            return ActionResult.success(this.getWorld().isClient());
        }

        if (stack.isOf(Items.CHORUS_FRUIT)) {
            if (this.dataTracker.get(PEEK_AMOUNT) <= 0) {
                this.playSound(SoundEvents.ENTITY_SHULKER_CLOSE, 0.7f, 1.1f);
                return ActionResult.FAIL;
            }
            if (!this.getWorld().isClient()) {
                ServerWorld serverWorld = (ServerWorld) this.getWorld();
                this.heal(6.0f);
                this.playSound(SoundEvents.ENTITY_GENERIC_EAT, 0.75f, 0.95f + this.random.nextFloat() * 0.15f);
                serverWorld.spawnParticles(
                        ParticleTypes.HEART,
                        this.getX(),
                        this.getBodyY(0.8d),
                        this.getZ(),
                        5,
                        0.35d,
                        0.2d,
                        0.35d,
                        0.02d
                );
                if (this.getHealth() >= this.getMaxHealth() - FULL_HEALTH_EPSILON) {
                    this.startHappySpin();
                    serverWorld.spawnParticles(
                            ParticleTypes.HAPPY_VILLAGER,
                            this.getX(),
                            this.getBodyY(0.9d),
                            this.getZ(),
                            8,
                            0.4d,
                            0.25d,
                            0.4d,
                            0.03d
                    );
                }
                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }
            }
            return ActionResult.success(this.getWorld().isClient());
        }
        return super.interactMob(player, hand);
    }

    private void startHappySpin() {
        this.happySpinTicks = 60;
        this.happyArpeggioAscending = this.random.nextBoolean();
        this.happyArpeggioStep = this.happyArpeggioAscending ? 0 : HAPPY_ARPEGGIO_PITCHES.length - 1;
        this.playSound(SoundEvents.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.9f, 1.2f);
    }

    private void playHappyArpeggioTick() {
        if (this.getWorld().isClient() || this.happySpinTicks % HAPPY_ARPEGGIO_INTERVAL_TICKS != 0) {
            return;
        }
        int idx = this.happyArpeggioStep;
        float pitch = HAPPY_ARPEGGIO_PITCHES[idx] * (0.99f + this.random.nextFloat() * 0.04f);
        this.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 0.5f, pitch);
        if (this.happyArpeggioAscending) {
            if (this.happyArpeggioStep >= HAPPY_ARPEGGIO_PITCHES.length - 1) {
                this.happyArpeggioAscending = false;
                if (HAPPY_ARPEGGIO_PITCHES.length > 1) {
                    this.happyArpeggioStep--;
                }
            } else {
                this.happyArpeggioStep++;
            }
        } else {
            if (this.happyArpeggioStep <= 0) {
                this.happyArpeggioAscending = true;
                if (HAPPY_ARPEGGIO_PITCHES.length > 1) {
                    this.happyArpeggioStep++;
                }
            } else {
                this.happyArpeggioStep--;
            }
        }
        if (this.getWorld() instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(
                    ParticleTypes.NOTE,
                    this.getX(),
                    this.getBodyY(1.0d),
                    this.getZ(),
                    2,
                    this.random.nextDouble(),
                    0.0d,
                    0.0d,
                    1.0d
            );
        }
    }

    @Override
    public boolean canTarget(LivingEntity target) {
        return this.isValidCombatTarget(target) && super.canTarget(target);
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    private void fireSparkVolley(LivingEntity target) {
        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }

        // Fire 3 sparks at once
        for (int i = 0; i < 3; i++) {
            PeekabooSparkEntity spark = new PeekabooSparkEntity(serverWorld, this, target);
            serverWorld.spawnEntity(spark);
        }

        this.playSound(SoundEvents.ENTITY_SHULKER_SHOOT, 1.0f, 1.0f + (this.random.nextFloat() - 0.5f) * 0.12f);
        serverWorld.spawnParticles(
                new DustParticleEffect(new Vector3f(0.95f, 0.85f, 0.25f), 1.0f),
                this.getX(),
                this.getBodyY(0.6d),
                this.getZ(),
                16,
                0.3d,
                0.2d,
                0.3d,
                0.02d
        );
    }

    private LivingEntity findPriorityTarget() {
        List<LivingEntity> candidates = this.getWorld().getEntitiesByClass(
                LivingEntity.class,
                this.getBoundingBox().expand(TARGET_SEARCH_RANGE),
                target -> this.isValidCombatTarget(target)
                        && this.canDirectlySee(target)
                        && this.isTargetWithinEngagePathDistance(target)
        );
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(this::targetScore))
                .orElse(null);
    }

    private boolean canDirectlySee(LivingEntity target) {
        Vec3d eyePos = new Vec3d(this.getX(), this.getBodyY(0.65d), this.getZ());
        Vec3d targetCenter = target.getEyePos();
        Vec3d diff = targetCenter.subtract(eyePos);
        double distance = diff.length();
        if (distance < 0.5d) {
            return true;
        }
        // Raycast through blocks to check line of sight
        return this.getWorld().raycast(new net.minecraft.world.RaycastContext(
                eyePos,
                targetCenter,
                net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                net.minecraft.world.RaycastContext.FluidHandling.NONE,
                this
        )).getType() == net.minecraft.util.hit.HitResult.Type.MISS;
    }

    private double targetScore(LivingEntity entity) {
        double distancePart = this.squaredDistanceTo(entity);
        if (entity instanceof RaiderEntity) {
            return distancePart;
        }
        if (entity instanceof CreeperEntity) {
            return 1000.0d + distancePart;
        }
        return 2000.0d + distancePart;
    }

    private boolean isValidCombatTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (target == this || target instanceof PeekabooShellEntity || target instanceof ShulkerEntity) {
            return false;
        }
        if (target instanceof PlayerEntity || target instanceof GolemEntity) {
            return false;
        }
        return target instanceof RaiderEntity || target instanceof HostileEntity || target instanceof CreeperEntity;
    }

    private boolean isTargetWithinEngagePathDistance(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (!(target instanceof MobEntity mobTarget)) {
            return this.squaredDistanceTo(target) <= MAX_ENGAGE_PATH_DISTANCE * MAX_ENGAGE_PATH_DISTANCE;
        }
        Path path = mobTarget.getNavigation().findPathTo(this, 0);
        if (path == null) {
            return false;
        }

        double pathDistance = 0.0d;
        Vec3d previous = mobTarget.getPos();
        for (int i = 0; i < path.getLength(); i++) {
            PathNode node = path.getNode(i);
            Vec3d nodePos = new Vec3d(node.x + 0.5d, node.y, node.z + 0.5d);
            pathDistance += previous.distanceTo(nodePos);
            if (pathDistance > MAX_ENGAGE_PATH_DISTANCE) {
                return false;
            }
            previous = nodePos;
        }
        pathDistance += previous.distanceTo(this.getPos());
        return pathDistance <= MAX_ENGAGE_PATH_DISTANCE;
    }

    private void tryNonCombatTeleportPreference() {
        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        if (serverWorld.getRegistryKey() != World.OVERWORLD) {
            return;
        }
        if (this.isComfortableIdleSpot(serverWorld)) {
            return;
        }

        boolean sunnyDay = serverWorld.isDay() && !serverWorld.isRaining();
        BlockPos preferred = sunnyDay ? findSunnyPos(serverWorld) : findBedNearby(serverWorld);
        if (preferred != null && this.tryTeleportToTopFace(serverWorld, preferred)) {
            return;
        }
        if (this.random.nextFloat() < 0.35f) {
            this.tryTeleportWithBehaviorSound(0.9f, 1.0f);
        }
    }

    private boolean isComfortableIdleSpot(ServerWorld world) {
        BlockPos current = this.getBlockPos();
        BlockPos groundPos = current.down();
        BlockState ground = world.getBlockState(groundPos);
        if (!ground.isSideSolidFullSquare(world, groundPos, Direction.UP) || isForbiddenAttachBlock(ground)) {
            return false;
        }
        if (!world.isAir(current.up())) {
            return false;
        }

        boolean sunnyDay = world.isDay() && !world.isRaining();
        if (sunnyDay) {
            return world.isSkyVisible(current.up());
        }

        BlockPos bed = this.findBedNearby(world);
        return bed == null || bed.getSquaredDistance(current) <= 100.0d;
    }

    private void resetNonCombatTeleportCooldown() {
        this.dayTeleportCooldownTicks = this.random.nextBetween(
                NON_COMBAT_TELEPORT_COOLDOWN_MIN,
                NON_COMBAT_TELEPORT_COOLDOWN_MAX
        );
    }

    private boolean tryCuriousStacking() {
        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }
        if (serverWorld.getRegistryKey() != World.OVERWORLD || !serverWorld.isDay() || serverWorld.isRaining()) {
            return false;
        }
        if (this.getTarget() != null) {
            return false;
        }

        List<PeekabooShellEntity> friends = serverWorld.getEntitiesByClass(
                PeekabooShellEntity.class,
                this.getBoundingBox().expand(16.0d),
                friend -> friend != this && friend.isAlive() && friend.getTarget() == null
        );
        if (friends.isEmpty()) {
            return false;
        }

        PeekabooShellEntity partner = friends.stream()
                .min(Comparator.comparingDouble(this::squaredDistanceTo))
                .orElse(null);
        if (partner == null) {
            return false;
        }

        int stackCount = this.countStackAt(partner);
        if (stackCount >= MAX_STACK_SIZE) {
            return false;
        }

        double topY = this.findStackTopY(partner);
        double targetX = partner.getX();
        double targetZ = partner.getZ();
        double targetY = topY + this.getHeight() * 0.95d;
        if (this.squaredDistanceTo(targetX, targetY, targetZ) < 1.0d) {
            return false;
        }

        this.refreshPositionAndAngles(targetX, targetY, targetZ, this.getYaw(), this.getPitch());
        this.setVelocity(Vec3d.ZERO);
        this.velocityDirty = true;
        this.playSound(SoundEvents.ENTITY_SHULKER_TELEPORT, 0.75f, 1.25f);
        return true;
    }

    private boolean tryScatterWhenPlayerNear() {
        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return false;
        }
        if (!this.isUpperShellInStack()) {
            return false;
        }
        PlayerEntity nearest = serverWorld.getClosestPlayer(this, 4.5d);
        if (nearest == null || nearest.isSpectator()) {
            return false;
        }

        Vec3d away = this.getPos().subtract(nearest.getPos());
        if (away.lengthSquared() < 1.0E-5d) {
            away = new Vec3d(this.random.nextDouble() - 0.5d, 0.0d, this.random.nextDouble() - 0.5d);
        }
        away = away.normalize();

        for (int i = 0; i < 20; i++) {
            double distance = this.random.nextBetween(4, 10);
            BlockPos candidate = BlockPos.ofFloored(
                    this.getX() + away.x * distance + this.random.nextBetween(-2, 2),
                    this.getY() + this.random.nextBetween(-2, 2),
                    this.getZ() + away.z * distance + this.random.nextBetween(-2, 2)
            );
            if (this.tryTeleportToTopFace(serverWorld, candidate)) {
                return true;
            }
        }
        this.tryTeleportWithBehaviorSound(0.9f, 1.15f);
        return true;
    }

    private boolean isUpperShellInStack() {
        List<PeekabooShellEntity> nearbyStack = this.getWorld().getEntitiesByClass(
                PeekabooShellEntity.class,
                this.getBoundingBox().expand(0.35d, MAX_STACK_SIZE * 1.2d, 0.35d),
                shell -> shell != this
                        && shell.isAlive()
                        && Math.abs(shell.getX() - this.getX()) < 0.35d
                        && Math.abs(shell.getZ() - this.getZ()) < 0.35d
        );
        for (PeekabooShellEntity shell : nearbyStack) {
            if (shell.getY() < this.getY() - 0.45d) {
                return true;
            }
        }
        return false;
    }

    private void tryTopStepNudge() {
        if (this.dataTracker.get(PEEK_AMOUNT) > 0) {
            return;
        }
        if (this.topStepNudgeCooldownTicks > 0) {
            this.topStepNudgeCooldownTicks--;
            return;
        }

        Box topBox = new Box(
                this.getBoundingBox().minX + 0.08d,
                this.getBoundingBox().maxY - 0.03d,
                this.getBoundingBox().minZ + 0.08d,
                this.getBoundingBox().maxX - 0.08d,
                this.getBoundingBox().maxY + 0.32d,
                this.getBoundingBox().maxZ - 0.08d
        );
        List<LivingEntity> steppingEntities = this.getWorld().getEntitiesByClass(
                LivingEntity.class,
                topBox,
                entity -> entity != this && entity.isAlive() && !entity.isSpectator()
        );
        if (steppingEntities.isEmpty()) {
            return;
        }

        this.topStepNudgeCooldownTicks = 20;
        this.setBodyYaw(this.getBodyYaw() + (this.random.nextBoolean() ? 10.0f : -10.0f));
        this.playSound(SoundEvents.ENTITY_SHULKER_OPEN, 0.55f, 1.4f);
        for (LivingEntity stepping : steppingEntities) {
            Vec3d push = stepping.getPos().subtract(this.getPos());
            Vec3d horizontal = new Vec3d(push.x, 0.0d, push.z);
            if (horizontal.lengthSquared() < 1.0E-4d) {
                horizontal = new Vec3d(this.random.nextDouble() - 0.5d, 0.0d, this.random.nextDouble() - 0.5d);
            }
            horizontal = horizontal.normalize().multiply(0.28d);
            stepping.addVelocity(horizontal.x, 0.24d, horizontal.z);
            stepping.velocityModified = true;
        }
    }

    private BlockPos findSunnyPos(ServerWorld world) {
        BlockPos origin = this.getBlockPos();
        for (int i = 0; i < 32; i++) {
            BlockPos candidate = origin.add(
                    this.random.nextBetween(-10, 10),
                    this.random.nextBetween(-3, 6),
                    this.random.nextBetween(-10, 10)
            );
            if (world.isSkyVisible(candidate.up())) {
                return candidate;
            }
        }
        return null;
    }

    private BlockPos findBedNearby(ServerWorld world) {
        BlockPos origin = this.getBlockPos();
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int x = -12; x <= 12; x++) {
            for (int y = -4; y <= 4; y++) {
                for (int z = -12; z <= 12; z++) {
                    mutable.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (world.getBlockState(mutable).isIn(net.minecraft.registry.tag.BlockTags.BEDS)) {
                        return mutable.toImmutable();
                    }
                }
            }
        }
        return null;
    }

    private boolean tryTeleportToTopFace(ServerWorld world, BlockPos center) {
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int i = 0; i < 30; i++) {
            int dx = this.random.nextBetween(-6, 6);
            int dz = this.random.nextBetween(-6, 6);
            int y = MathHelper.clamp(center.getY() + this.random.nextBetween(-3, 3), world.getBottomY() + 1, world.getTopY() - 2);
            mutable.set(center.getX() + dx, y, center.getZ() + dz);
            BlockPos groundPos = mutable.down();
            BlockState groundState = world.getBlockState(groundPos);
            if (!groundState.isSideSolidFullSquare(world, groundPos, Direction.UP) || isForbiddenAttachBlock(groundState)) {
                continue;
            }
            if (!world.isAir(mutable) || !world.isAir(mutable.up())) {
                continue;
            }
            this.refreshPositionAndAngles(
                    mutable.getX() + 0.5d,
                    mutable.getY(),
                    mutable.getZ() + 0.5d,
                    this.getYaw(),
                    this.getPitch()
            );
            this.dataTracker.set(ATTACHED_FACE, Direction.UP);
            this.playSound(SoundEvents.ENTITY_SHULKER_TELEPORT, 1.0f, 1.0f);
            return true;
        }
        return false;
    }

    private boolean isForbiddenAttachBlock(BlockState state) {
        return state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                || state.isOf(Blocks.HONEY_BLOCK)
                || state.isOf(Blocks.SLIME_BLOCK);
    }

    private int countStackAt(PeekabooShellEntity base) {
        return this.getWorld().getEntitiesByClass(
                PeekabooShellEntity.class,
                base.getBoundingBox().expand(0.35d, MAX_STACK_SIZE * 1.2d, 0.35d),
                shell -> shell.isAlive() && Math.abs(shell.getX() - base.getX()) < 0.35d && Math.abs(shell.getZ() - base.getZ()) < 0.35d
        ).size();
    }

    private double findStackTopY(PeekabooShellEntity base) {
        double top = base.getY();
        List<PeekabooShellEntity> stacked = this.getWorld().getEntitiesByClass(
                PeekabooShellEntity.class,
                base.getBoundingBox().expand(0.35d, MAX_STACK_SIZE * 1.2d, 0.35d),
                shell -> shell.isAlive() && Math.abs(shell.getX() - base.getX()) < 0.35d && Math.abs(shell.getZ() - base.getZ()) < 0.35d
        );
        for (PeekabooShellEntity shell : stacked) {
            if (shell.getY() > top) {
                top = shell.getY();
            }
        }
        return top;
    }

    private void setPeekAmountRaw(int peekAmount) {
        this.dataTracker.set(PEEK_AMOUNT, (byte) MathHelper.clamp(peekAmount, 0, 100));
    }

    public void triggerEmergencyTeleport() {
        this.tryTeleportWithBehaviorSound(1.0f, 1.0f);
    }

    private boolean tryTeleportWithBehaviorSound(float volume, float pitch) {
        boolean teleported = this.tryTeleport();
        if (teleported) {
            this.playSound(SoundEvents.ENTITY_SHULKER_TELEPORT, volume, pitch);
        }
        return teleported;
    }

    @Override
    public DyeColor getColor() {
        DyeColor color = super.getColor();
        return color != null ? color : DyeColor.PURPLE;
    }
}
