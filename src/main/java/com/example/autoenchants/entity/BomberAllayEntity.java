package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
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
import net.minecraft.entity.ItemEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
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
    /** 寻敌与寻找 TNT 补给的搜索半径保持一致，避免「能看见目标却找不到补给」或反之。 */
    private static final double SEARCH_RANGE = 256.0d;
    private static final double TNT_SEARCH_RANGE = SEARCH_RANGE;
    private static final double TARGET_SEARCH_RANGE = SEARCH_RANGE;
    /** 单个悦灵主手上可携带的 TNT 上限。 */
    private static final int MAX_HELD_TNT = 8;
    /** 目标评分随机扰动范围：足以让不同悦灵选不同目标，但小于 RaiderEntity 优先加成。 */
    private static final double TARGET_SCORE_NOISE = 100.0d;
    private static final double DROP_HEIGHT_TARGET = 9.5d;
    private static final double DROP_HEIGHT_MIN = 6.5d;
    private static final double DROP_HORIZONTAL_TOLERANCE = 1.2d;
    /** 在目标正上方扫描方块的最大距离，用于判断是否需要先炸毁顶上的方块。 */
    private static final int OBSTRUCTION_SCAN_HEIGHT = 16;
    private static final int BOMB_COOLDOWN_TICKS = 60;
    /** 刚炸过的目标在多久内会被评分惩罚，使下一枚 TNT 优先投到别的目标。 */
    private static final int RECENT_BOMB_AVOID_TICKS = 100;
    private static final double RECENT_BOMB_PENALTY = 150.0d;
    /** 重新发起寻路的最短间隔，避免每 tick 都重算路径。投弹状态需要更稳定，间隔更长。 */
    private static final int REPATH_INTERVAL = 10;
    private static final int REPATH_INTERVAL_BOMBING = 20;
    /** 目标移动超过此距离才重新计算 aimY，避免高度频繁抖动。 */
    private static final double AIMY_RECALC_THRESHOLD = 2.0d;
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
    private java.util.UUID lastBombedTargetUuid;
    private int lastBombedTargetCooldown;
    /** 缓存的目标投弹高度，只有当目标显著移动时才重新计算。 */
    private double cachedTargetAimY = Double.NaN;
    private double cachedTargetX = Double.NaN;
    private double cachedTargetZ = Double.NaN;

    public BomberAllayEntity(EntityType<? extends AllayEntity> entityType, World world) {
        super(entityType, world);
        // 禁用原版自动拾取，通过 tickPickupState 手动处理 TNT 拾取以严格控制数量
        this.setCanPickUpLoot(false);
        // 适度提升飞行/移动速度：原版 Allay 默认 0.1，这里设为 0.25 加快寻敌与补给往返。
        EntityAttributeInstance flying = this.getAttributeInstance(EntityAttributes.GENERIC_FLYING_SPEED);
        if (flying != null) {
            flying.setBaseValue(0.25d);
        }
        EntityAttributeInstance moving = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (moving != null) {
            moving.setBaseValue(0.2d);
        }
    }

    private ItemStack getHeldStack() {
        // 使用 MobEntity 的主手装备槽：会通过 EntityEquipmentUpdateS2CPacket 自动同步到客户端，
        // 这样客户端的 HeldItemFeatureRenderer 才能正确显示手持的 TNT。
        // 不要使用 AllayEntity#getInventory()——SimpleInventory 仅服务端存在。
        return this.getEquippedStack(EquipmentSlot.MAINHAND);
    }

    private void setHeldStack(ItemStack stack) {
        this.equipStack(EquipmentSlot.MAINHAND, stack);
    }

    /**
     * 阻止原版 Allay 的自动拾取行为。
     * 投弹悦灵只能通过 tickPickupState 中的 tryPickupTnt 方法拾取 TNT，且严格限制 8 个上限。
     */
    @Override
    protected void pickUpItem(ServerWorld world, ItemEntity item) {
        // 完全阻止原版拾取逻辑，所有拾取由 tickPickupState 中的自定义逻辑控制
        return;
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
        if (lastBombedTargetCooldown > 0) {
            lastBombedTargetCooldown--;
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
        // 空手状态下飞行速度提高，加快去拿 TNT 补给。
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
        ItemStack held = getHeldStack();
        int currentCount = held.isOf(Items.TNT) ? held.getCount() : 0;
        int canTake = Math.min(stack.getCount(), MAX_HELD_TNT - currentCount);
        if (canTake <= 0) {
            return;
        }
        setHeldStack(new ItemStack(Items.TNT, currentCount + canTake));

        stack.decrement(canTake);
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

        // 若目标正上方有方块阻挡，将瞄点抬高至阻挡之上：悦灵会先飞到阻挡上方投弹，
        // 把顶上的方块炸掉清出通道，下一轮再下降至常规高度命中目标本体。
        double aimX = currentTarget.getX();
        double aimZ = currentTarget.getZ();

        // 只有当目标显著移动时才重新计算 aimY，避免高度频繁抖动导致晃动。
        double targetMovedSq = (aimX - cachedTargetX) * (aimX - cachedTargetX)
                + (aimZ - cachedTargetZ) * (aimZ - cachedTargetZ);
        if (Double.isNaN(cachedTargetAimY) || targetMovedSq > AIMY_RECALC_THRESHOLD * AIMY_RECALC_THRESHOLD) {
            cachedTargetAimY = resolveAimY(currentTarget);
            cachedTargetX = aimX;
            cachedTargetZ = aimZ;
        }
        double aimY = cachedTargetAimY;

        // 检查水平位置是否已经就位，若已水平对准则降低导航更新频率，减少晃动。
        double dx = this.getX() - aimX;
        double dz = this.getZ() - aimZ;
        double horizontalSq = dx * dx + dz * dz;
        boolean isInPosition = horizontalSq <= DROP_HORIZONTAL_TOLERANCE * DROP_HORIZONTAL_TOLERANCE * 4.0d;

        // 真正寻路（BirdNavigation 会在 3D 空间绕开方块）。
        // 若已就位则降低速度乘数，让移动更平滑。
        double speed = isInPosition ? 0.6d : 1.0d;
        navigateTo(aimX, aimY, aimZ, speed, isInPosition ? REPATH_INTERVAL_BOMBING : REPATH_INTERVAL);
        this.getLookControl().lookAt(currentTarget.getX(), currentTarget.getY(), currentTarget.getZ());

        if (bombCooldown > 0) {
            return;
        }

        // 复用上方已计算的 dx, dz, horizontalSq 和 isInPosition
        double dy = this.getY() - currentTarget.getY();
        // 仅约束最低投弹高度：太低 TNT 会把悦灵自己炸到。上限不再设置——
        // 太高时 TNT 自由落体会先撞到目标顶上的方块并爆炸（BomberTntEntity 触地即爆），
        // 正好用来把阻挡物清掉，符合「先炸毁目标上空方块」的诉求。
        if (horizontalSq <= DROP_HORIZONTAL_TOLERANCE * DROP_HORIZONTAL_TOLERANCE
                && dy >= DROP_HEIGHT_MIN) {
            dropTnt();
        }
    }

    /**
     * 解算瞄准的 Y 坐标：找到目标正上方第一格非空气方块的位置，瞄到「该方块再往上 DROP_HEIGHT_TARGET 格」；
     * 若头顶通畅则直接 target.y + DROP_HEIGHT_TARGET。这样在被天花板罩住的目标上空也有可投弹位置。
     */
    private double resolveAimY(LivingEntity target) {
        World world = this.getWorld();
        int tx = MathHelper.floor(target.getX());
        int tz = MathHelper.floor(target.getZ());
        int yStart = MathHelper.floor(target.getY() + target.getHeight() + 0.5d);
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        int topObstruction = -1;
        for (int dy = 0; dy < OBSTRUCTION_SCAN_HEIGHT; dy++) {
            int y = yStart + dy;
            cursor.set(tx, y, tz);
            BlockState state = world.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            if (state.getCollisionShape(world, cursor).isEmpty()) {
                continue;
            }
            topObstruction = y;
        }
        if (topObstruction >= 0) {
            return topObstruction + 1 + DROP_HEIGHT_TARGET;
        }
        return target.getY() + DROP_HEIGHT_TARGET;
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
        // 刚炸过的目标在冷却期内被惩罚：下一枚 TNT 优先投向别的目标。
        // 若仅有这一个可选目标，惩罚仍小于距离差异与 Raider 加成，仍会被重新选中。
        if (lastBombedTargetCooldown > 0 && lastBombedTargetUuid != null
                && lastBombedTargetUuid.equals(entity.getUuid())) {
            score -= RECENT_BOMB_PENALTY;
        }
        // 添加随机扰动：多只悦灵同时选择时不会都锁同一个目标。每次重扫（SCAN_INTERVAL_FOUND=20t）
        // 重新采样，配合 currentTarget 的 +30 粘性避免频繁切换。
        score += this.random.nextDouble() * TARGET_SCORE_NOISE;
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

        // 每次只消耗 1 个 TNT；剩余数量留在主手以供后续投弹（最多携带 MAX_HELD_TNT 个）。
        // 使用 setHeldStack 重新设置，确保 EntityEquipmentUpdateS2CPacket 同步到客户端。
        int remaining = held.getCount() - 1;
        setHeldStack(remaining > 0 ? new ItemStack(Items.TNT, remaining) : ItemStack.EMPTY);

        // 记录刚炸的目标，下一轮选择会优先避开他；同时清除当前目标以强制重新扫描。
        if (currentTarget != null) {
            lastBombedTargetUuid = currentTarget.getUuid();
            lastBombedTargetCooldown = RECENT_BOMB_AVOID_TICKS;
        }
        currentTarget = null;
        // 清除缓存的高度，以便下次选择新目标时重新计算
        cachedTargetAimY = Double.NaN;
        cachedTargetX = Double.NaN;
        cachedTargetZ = Double.NaN;
        bombScanCooldown = 0;

        world.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.NEUTRAL, 0.8f, 1.2f);
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.SMOKE,
                    this.getX(), this.getY() - 0.3d, this.getZ(),
                    8, 0.15d, 0.05d, 0.15d, 0.02d);
        }

        bombCooldown = BOMB_COOLDOWN_TICKS;

        // 投弹后适度向上加速远离爆炸范围，但不宜过强以免与导航冲突导致晃动。
        this.setVelocity(this.getVelocity().x * 0.2d, 0.25d, this.getVelocity().z * 0.2d);
        this.velocityModified = true;
        this.getNavigation().stop();
        // 延长冷却，让悦灵有时间稳定后再重新寻路
        repathCooldown = 30;
    }

    /**
     * 通过 Navigation 进行真正的 A* 寻路；只在路径完成或冷却结束时重新发路径，避免抖动。
     */
    private void navigateTo(double x, double y, double z, double speed) {
        navigateTo(x, y, z, speed, REPATH_INTERVAL);
    }

    /**
     * 通过 Navigation 进行真正的 A* 寻路，可指定重路径间隔。
     */
    private void navigateTo(double x, double y, double z, double speed, int interval) {
        if (repathCooldown > 0 && !this.getNavigation().isIdle()) {
            return;
        }
        this.getNavigation().startMovingTo(x, y, z, speed);
        repathCooldown = interval;
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
        navigateTo(this.getX() + dx, this.getY() + dy, this.getZ() + dz, 0.6d);
    }
}
