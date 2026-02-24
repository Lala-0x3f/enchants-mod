package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
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
    private static final int SEARCH_INTERVAL_TICKS = 20;
    private static final int IDLE_PEEK_AMOUNT = 35;
    private static final int COMBAT_PEEK_AMOUNT = 100;
    private static final int MIN_SHOT_INTERVAL = 20;
    private static final int MAX_SHOT_INTERVAL = 110;
    private static final int DAY_TELEPORT_INTERVAL = 120;
    private static final int PURR_INTERVAL_MIN = 80;
    private static final int PURR_INTERVAL_MAX = 180;
    private static final float CLOSED_DAMAGE_FACTOR = 0.35f;
    private static final int SOCIAL_INTERVAL_MIN = 30;
    private static final int SOCIAL_INTERVAL_MAX = 60;
    private static final float STACK_TRIGGER_CHANCE = 0.18f;
    private static final int MAX_STACK_SIZE = 8;

    private int shotCooldownTicks = MIN_SHOT_INTERVAL;
    private int searchCooldownTicks = 5;
    private int dayTeleportCooldownTicks = DAY_TELEPORT_INTERVAL;
    private int purrCooldownTicks = 60;
    private int socialCooldownTicks = 20;
    private int topStepNudgeCooldownTicks = 0;
    private int happySpinTicks = 0;

    public PeekabooShellEntity(EntityType<? extends PeekabooShellEntity> entityType, World world) {
        super(entityType, world);
        this.experiencePoints = 8;
    }

    @Override
    protected void initGoals() {
        super.initGoals();
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
            this.setPeekAmountRaw(COMBAT_PEEK_AMOUNT);
            if (--this.shotCooldownTicks <= 0) {
                this.shotCooldownTicks = this.random.nextBetween(MIN_SHOT_INTERVAL, MAX_SHOT_INTERVAL);
                this.fireSparkVolley(target);
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
            this.dayTeleportCooldownTicks = DAY_TELEPORT_INTERVAL;
            this.tryNonCombatTeleportPreference();
        }
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        Entity attacker = source.getSource();
        if (attacker instanceof ShulkerBulletEntity bullet
                && !bullet.getCommandTags().contains(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG)) {
            return false;
        }
        if (attacker instanceof ShulkerBulletEntity bullet
                && bullet.getCommandTags().contains(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG)) {
            this.tryTeleport();
            return false;
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
        return super.damage(source, amount);
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
            if (!this.getWorld().isClient()) {
                float before = this.getHealth();
                this.heal(6.0f);
                if (before < this.getMaxHealth() && this.getHealth() >= this.getMaxHealth()) {
                    this.happySpinTicks = 60;
                    this.playSound(SoundEvents.ENTITY_ALLAY_AMBIENT_WITH_ITEM, 0.9f, 1.2f);
                }
                if (!player.getAbilities().creativeMode) {
                    stack.decrement(1);
                }
            }
            return ActionResult.success(this.getWorld().isClient());
        }
        return super.interactMob(player, hand);
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

        for (int i = 0; i < 3; i++) {
            Direction.Axis axis = Direction.Axis.pickRandomAxis(this.random);
            ShulkerBulletEntity bullet = new ShulkerBulletEntity(this.getWorld(), this, target, axis);
            bullet.addCommandTag(AutoEnchantsMod.PEEKABOO_SHELL_SPARK_TAG);
            bullet.refreshPositionAndAngles(
                    this.getX(),
                    this.getBodyY(0.65d),
                    this.getZ(),
                    this.getYaw(),
                    this.getPitch()
            );
            this.getWorld().spawnEntity(bullet);
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
                this.getBoundingBox().expand(32.0d),
                this::isValidCombatTarget
        );
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .min(Comparator.comparingDouble(this::targetScore))
                .orElse(null);
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

    private void tryNonCombatTeleportPreference() {
        if (!(this.getWorld() instanceof ServerWorld serverWorld)) {
            return;
        }
        if (serverWorld.getRegistryKey() != World.OVERWORLD) {
            this.tryTeleport();
            return;
        }

        boolean sunnyDay = serverWorld.isDay() && !serverWorld.isRaining();
        BlockPos preferred = sunnyDay ? findSunnyPos(serverWorld) : findBedNearby(serverWorld);
        if (preferred != null && this.tryTeleportToTopFace(serverWorld, preferred)) {
            return;
        }
        this.tryTeleport();
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
        this.tryTeleport();
        return true;
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
        this.tryTeleport();
    }

    @Override
    public DyeColor getColor() {
        return this.getVariant().orElse(DyeColor.PURPLE);
    }
}
