package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.entity.mob.FlyingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.passive.BatEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Comparator;
import java.util.List;

/**
 * 投弹悦灵：友好飞行生物。
 * - 主手为空时：在大范围内寻找地面上的 TNT 物品并飞过去拾取（仅拾取 TNT）。
 * - 主手持有 TNT 时：选择敌对地面生物（优先灾厄村民）作为目标，飞至目标头顶约 8 格高处，
 *   投下 {@link BomberTntEntity}（触地立即爆炸）。
 * - 不会主动攻击玩家或飞行生物。
 */
public class BomberAllayEntity extends AllayEntity {
    private static final double TNT_SEARCH_RANGE = 96.0d;
    private static final double TARGET_SEARCH_RANGE = 192.0d;
    private static final double DROP_HEIGHT_TARGET = 9.5d;
    private static final double DROP_HEIGHT_MIN = 8.5d;
    private static final double DROP_HEIGHT_MAX = 11.5d;
    private static final double DROP_HORIZONTAL_TOLERANCE = 1.2d;
    private static final int BOMB_COOLDOWN_TICKS = 30;
    /** 重新发起寻路的最短间隔，避免每 tick 都重算路径。 */
    private static final int REPATH_INTERVAL = 10;
    /** 扫描到有效目标/物品后的刷新间隔。 */
    private static final int SCAN_INTERVAL_FOUND = 20;
    /** 扫描为空后的退避间隔，避免空场景下高频全量扫。 */
    private static final int SCAN_INTERVAL_EMPTY = 60;
    private static final int IDLE_WANDER_INTERVAL = 80;

    private int bombCooldown;
    private int bombScanCooldown;
    private int pickupScanCooldown;
    private int idleWanderTicks;
    private int repathCooldown;
    private LivingEntity currentTarget;
    private ItemEntity currentTntItem;

    public BomberAllayEntity(EntityType<? extends AllayEntity> entityType, World world) {
        super(entityType, world);
        this.setCanPickUpLoot(true);
    }

    private ItemStack getHeldStack() {
        return this.getInventory().getStack(0);
    }

    private void setHeldStack(ItemStack stack) {
        this.getInventory().setStack(0, stack);
    }

    @Override
    protected void initGoals() {
        // 仅保留游泳目标；其余行为通过 tick() 自定义状态机驱动。
        this.goalSelector.add(0, new SwimGoal(this));
    }

    @Override
    protected void mobTick() {
        // 跳过原版 Allay 基于 Brain 的行为（跟随玩家/复制/收集匹配物品等）。
        // 自定义状态机在 tick() 中执行。
    }

    @Override
    public void tick() {
        super.tick();

        if (this.getWorld().isClient()) {
            return;
        }

        if (bombCooldown > 0) {
            bombCooldown--;
        }
        if (bombScanCooldown > 0) {
            bombScanCooldown--;
        }
        if (pickupScanCooldown > 0) {
            pickupScanCooldown--;
        }
        if (idleWanderTicks > 0) {
            idleWanderTicks--;
        }
        if (repathCooldown > 0) {
            repathCooldown--;
        }

        if (getHeldStack().isOf(Items.TNT)) {
            tickBombingState();
        } else {
            tickPickupState();
        }
    }

    /* ---------------- Pickup state ---------------- */

    private void tickPickupState() {
        // 当前物品丢失（被别人拾走/衰减）时立即允许重扫。
        if (currentTntItem != null && (!currentTntItem.isAlive()
                || !currentTntItem.getStack().isOf(Items.TNT))) {
            currentTntItem = null;
            pickupScanCooldown = 0;
        }

        if (pickupScanCooldown <= 0) {
            ItemEntity found = findNearestTntItem();
            currentTntItem = found;
            pickupScanCooldown = (found != null) ? SCAN_INTERVAL_FOUND : SCAN_INTERVAL_EMPTY;
        }

        if (currentTntItem == null) {
            tickIdleWander();
            return;
        }

        Vec3d itemPos = currentTntItem.getPos();
        // 用 Navigation 进行真正的寻路，绕开墙体；MoveControl 只能直线推进会卡墙。
        navigateTo(itemPos.x, itemPos.y + 0.5d, itemPos.z, 1.2d);
        this.getLookControl().lookAt(itemPos.x, itemPos.y, itemPos.z);

        if (this.squaredDistanceTo(currentTntItem) <= 1.5d * 1.5d) {
            tryPickupTnt(currentTntItem);
            currentTntItem = null;
        }
    }

    private ItemEntity findNearestTntItem() {
        Box box = this.getBoundingBox().expand(TNT_SEARCH_RANGE);
        List<ItemEntity> candidates = this.getWorld().getEntitiesByClass(ItemEntity.class, box,
                e -> e.isAlive() && !e.cannotPickup() && e.getStack().isOf(Items.TNT));
        return candidates.stream()
                .min(Comparator.comparingDouble(this::squaredDistanceTo))
                .orElse(null);
    }

    private void tryPickupTnt(ItemEntity item) {
        ItemStack stack = item.getStack();
        if (stack.isEmpty() || !stack.isOf(Items.TNT)) {
            return;
        }
        setHeldStack(new ItemStack(Items.TNT, 1));

        stack.decrement(1);
        if (stack.isEmpty()) {
            item.discard();
        } else {
            item.setStack(stack);
        }

        this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.NEUTRAL, 0.5f,
                1.0f + (this.random.nextFloat() - this.random.nextFloat()) * 0.4f);
        if (this.getWorld() instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
                    this.getX(), this.getBodyY(0.6d), this.getZ(),
                    6, 0.25d, 0.25d, 0.25d, 0.0d);
        }
    }

    /* ---------------- Bombing state ---------------- */

    private void tickBombingState() {
        // 当前目标失效（死亡/移出范围/变为不可炸）时立即允许重扫。
        if (currentTarget != null && !isValidBombTarget(currentTarget)) {
            currentTarget = null;
            bombScanCooldown = 0;
        }

        if (bombScanCooldown <= 0) {
            LivingEntity found = acquireBestTarget();
            currentTarget = found;
            bombScanCooldown = (found != null) ? SCAN_INTERVAL_FOUND : SCAN_INTERVAL_EMPTY;
        }

        if (currentTarget == null) {
            tickIdleWander();
            return;
        }

        double aimX = currentTarget.getX();
        double aimY = currentTarget.getY() + DROP_HEIGHT_TARGET;
        double aimZ = currentTarget.getZ();

        // 真正寻路（BirdNavigation 会在 3D 空间绕开方块）。
        navigateTo(aimX, aimY, aimZ, 1.3d);
        this.getLookControl().lookAt(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ());

        if (bombCooldown > 0) {
            return;
        }

        double dx = this.getX() - currentTarget.getX();
        double dz = this.getZ() - currentTarget.getZ();
        double horizontalSq = dx * dx + dz * dz;
        double dy = this.getY() - currentTarget.getY();
        if (horizontalSq <= DROP_HORIZONTAL_TOLERANCE * DROP_HORIZONTAL_TOLERANCE
                && dy >= DROP_HEIGHT_MIN
                && dy <= DROP_HEIGHT_MAX
                && hasClearDropPath(currentTarget)) {
            dropTnt();
        }
    }

    private LivingEntity acquireBestTarget() {
        Box box = this.getBoundingBox().expand(TARGET_SEARCH_RANGE);
        List<LivingEntity> candidates = this.getWorld().getEntitiesByClass(LivingEntity.class, box, this::isValidBombTarget);
        LivingEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (LivingEntity entity : candidates) {
            double score = scoreTarget(entity);
            if (score > bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    private boolean isValidBombTarget(LivingEntity entity) {
        if (entity == this || !entity.isAlive() || entity.isSpectator() || entity.isRemoved()) {
            return false;
        }
        if (!(entity instanceof Monster)) {
            return false;
        }
        // 排除飞行/空中生物，仅针对地面行走的敌对生物。
        if (entity instanceof FlyingEntity              // 恶鬼 GhastEntity、幻翼 PhantomEntity
                || entity instanceof PhantomEntity       // 冲击保护（幻翼实际上是 FlyingEntity子类）
                || entity instanceof VexEntity            // 恼鬼
                || entity instanceof BlazeEntity          // 烈焰人
                || entity instanceof ShulkerEntity        // 潜影贝（不可被炸伤）
                || entity instanceof BatEntity
                || entity instanceof EnderDragonEntity) {
            return false;
        }
        return this.squaredDistanceTo(entity) <= TARGET_SEARCH_RANGE * TARGET_SEARCH_RANGE;
    }

    private double scoreTarget(LivingEntity entity) {
        double distSq = this.squaredDistanceTo(entity);
        double score = -distSq * 0.05d;
        // 灾厄村民优先（包括 Pillager/Vindicator/Evoker/Illusioner/Witch/Ravager）。
        if (entity instanceof RaiderEntity) {
            score += 200.0d;
        }
        if (currentTarget != null && currentTarget.getUuid().equals(entity.getUuid())) {
            score += 30.0d;
        }
        return score;
    }

    private void dropTnt() {
        ItemStack held = getHeldStack();
        if (!held.isOf(Items.TNT)) {
            return;
        }

        World world = this.getWorld();
        // 自定义 TNT：触地立即爆炸（不依赖引信倒计时）。
        BomberTntEntity tnt = new BomberTntEntity(AutoEnchantsMod.BOMBER_TNT, world);
        double spawnX = this.getX();
        double spawnY = this.getY() - 0.5d;
        double spawnZ = this.getZ();
        tnt.setPosition(spawnX, spawnY, spawnZ);
        tnt.prevX = spawnX;
        tnt.prevY = spawnY;
        tnt.prevZ = spawnZ;
        // 给一点向下的初速度以加速下落。
        tnt.setVelocity(0.0d, -0.1d, 0.0d);
        // 引信仅作为安全上限：万一卡墙未触地，最多 80t 后自爆，避免无限存在。
        tnt.setFuse(80);
        world.spawnEntity(tnt);

        held.decrement(1);
        if (held.isEmpty()) {
            setHeldStack(ItemStack.EMPTY);
        }

        world.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.NEUTRAL, 0.8f, 1.2f);
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.SMOKE,
                    this.getX(), this.getY() - 0.3d, this.getZ(),
                    8, 0.15d, 0.05d, 0.15d, 0.02d);
        }

        bombCooldown = BOMB_COOLDOWN_TICKS;

        // 投弹后立即向上加速，远离爆炸范围（vanilla TNT power=4，伤害半径约 8 格）。
        this.setVelocity(this.getVelocity().x * 0.2d, 0.6d, this.getVelocity().z * 0.2d);
        this.velocityModified = true;
        this.getNavigation().stop();
        repathCooldown = 10;
    }

    /**
     * 检查从悦灵到目标头顶之间是否没有方块阻挡，避免 TNT 一生成就撞到天花板/上方方块在悦灵附近爆炸。
     */
    private boolean hasClearDropPath(LivingEntity target) {
        Vec3d start = new Vec3d(this.getX(), this.getY() - 0.5d, this.getZ());
        Vec3d end = new Vec3d(target.getX(), target.getY() + target.getHeight() + 0.1d, target.getZ());
        BlockHitResult hit = this.getWorld().raycast(new RaycastContext(
                start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                this));
        return hit.getType() == HitResult.Type.MISS;
    }

    /**
     * 通过 Navigation 进行真正的 A* 寻路；只在路径完成或冷却结束时重新发路径，避免抖动。
     */
    private void navigateTo(double x, double y, double z, double speed) {
        if (repathCooldown > 0 && !this.getNavigation().isIdle()) {
            return;
        }
        this.getNavigation().startMovingTo(x, y, z, speed);
        repathCooldown = REPATH_INTERVAL;
    }

    /* ---------------- Idle ---------------- */

    private void tickIdleWander() {
        if (idleWanderTicks > 0) {
            return;
        }
        idleWanderTicks = IDLE_WANDER_INTERVAL + this.random.nextInt(80);

        double dx = (this.random.nextDouble() - 0.5d) * 8.0d;
        double dy = (this.random.nextDouble() - 0.5d) * 4.0d;
        double dz = (this.random.nextDouble() - 0.5d) * 8.0d;
        navigateTo(this.getX() + dx, this.getY() + dy, this.getZ() + dz, 0.8d);
    }
}
