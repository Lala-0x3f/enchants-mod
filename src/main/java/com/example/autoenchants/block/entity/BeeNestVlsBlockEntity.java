package com.example.autoenchants.block.entity;

import com.example.autoenchants.AutoEnchantsMod;
import com.example.autoenchants.block.BeeNestVlsBlock;
import com.example.autoenchants.entity.ArmorPiercingArrowEntity;
import com.example.autoenchants.entity.BeeMissileEntity;
import com.example.autoenchants.entity.StingerMissileEntity;
import com.example.autoenchants.item.StingerMissileItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

public class BeeNestVlsBlockEntity extends BlockEntity {
    private static final int CAPACITY = 12;
    private static final int FIRE_COOLDOWN_TICKS = 50;
    private static final int SCAN_INTERVAL_TICKS = 20; // 扫描间隔：20 ticks = 1秒
    private static final int LAUNCH_CLEARANCE_TICKS = 20;
    private static final double SEARCH_RANGE = 128.0d;
    private static final double LAUNCH_SPEED = 1.4d;

    private final DefaultedList<ItemStack> items = DefaultedList.ofSize(CAPACITY, ItemStack.EMPTY);
    private int fireCooldown;
    private int scanCooldown; // 扫描冷却，避免每tick都扫描

    public BeeNestVlsBlockEntity(BlockPos pos, BlockState state) {
        super(AutoEnchantsMod.BEE_NEST_VLS_BLOCK_ENTITY, pos, state);
    }

    public DefaultedList<ItemStack> getItems() {
        return items;
    }

    public boolean isAcceptedMissile(ItemStack stack) {
        return stack.isOf(AutoEnchantsMod.BEE_MISSILE_ITEM) || stack.isOf(AutoEnchantsMod.STINGER_MISSILE_ITEM);
    }

    public int getMissileCount() {
        int count = 0;
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) count++;
        }
        return count;
    }

    public boolean addMissile(ItemStack stack, boolean creative) {
        if (stack.isEmpty() || !isAcceptedMissile(stack)) return false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).isEmpty()) {
                ItemStack stored = stack.copy();
                stored.setCount(1);
                items.set(i, stored);
                if (!creative) {
                    stack.decrement(1);
                }
                markDirty();
                syncLoadedState();
                return true;
            }
        }
        return false;
    }

    public static void tick(World world, BlockPos pos, BlockState state, BeeNestVlsBlockEntity vls) {
        if (!(world instanceof ServerWorld serverWorld)) return;

        // 发射冷却倒计时
        if (vls.fireCooldown > 0) {
            vls.fireCooldown--;
        }

        // 扫描冷却倒计时
        if (vls.scanCooldown > 0) {
            vls.scanCooldown--;
            return;
        }

        // 基础条件检查
        if (!world.isReceivingRedstonePower(pos)) {
            return;
        }
        if (!world.getBlockState(pos.up()).isAir()) {
            return;
        }
        if (!vls.hasMissile()) {
            return;
        }
        if (vls.fireCooldown > 0) {
            return;
        }

        // 扫描目标
        Entity target = vls.findTarget(serverWorld, pos);

        if (target == null) {
            // 没有找到目标，设置扫描冷却
            vls.scanCooldown = SCAN_INTERVAL_TICKS;
            return;
        }

        // 找到目标，尝试发射
        if (vls.launch(serverWorld, pos, target)) {
            vls.fireCooldown = FIRE_COOLDOWN_TICKS;
            vls.scanCooldown = 0; // 发射成功后立即重置扫描冷却
        }
    }

    private boolean hasMissile() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return true;
        }
        return false;
    }

    @Nullable
    private Entity findTarget(ServerWorld world, BlockPos pos) {
        Vec3d origin = Vec3d.ofCenter(pos);
        Box searchBox = new Box(pos).expand(SEARCH_RANGE);

        // 第一步：获取范围内所有潜在敌对目标
        List<Entity> candidates = world.getOtherEntities(null, searchBox, e ->
                e.isAlive()
                        && !e.isSpectator()
                        && e.squaredDistanceTo(origin) <= SEARCH_RANGE * SEARCH_RANGE
                        && isHostileTarget(e)
        );

        // 如果没有任何敌对目标，返回 null（不发射）
        if (candidates.isEmpty()) {
            return null;
        }

        // 第二步：每次都创建新的寻路测试实体（避免缓存问题）
        // 让pathTester站在蜂巢方块的顶部表面（Y + 1.0）
        MobEntity pathTester = new net.minecraft.entity.mob.ZombieEntity(net.minecraft.entity.EntityType.ZOMBIE, world);
        pathTester.refreshPositionAndAngles(
                pos.getX() + 0.5,
                pos.getY() + 1.0,  // 蜂巢顶部表面
                pos.getZ() + 0.5,
                0.0f, 0.0f
        );

        // 第三步：筛选出寻路可达的目标，选择最优目标
        Entity bestTarget = null;
        double bestScore = Double.MAX_VALUE;

        for (Entity candidate : candidates) {
            // 验证是否可达
            if (!canPathfindTo(pathTester, candidate, world)) {
                continue;
            }

            // 计算优先级分数
            double score = scoreTarget(candidate, origin);
            if (score < bestScore) {
                bestScore = score;
                bestTarget = candidate;
            }
        }

        // 返回最优目标（如果所有目标都不可达，返回 null）
        return bestTarget;
    }

    private boolean isHostileTarget(Entity entity) {
        // 排除自己发射的导弹
        if (entity instanceof BeeMissileEntity || entity instanceof StingerMissileEntity) {
            return false;
        }

        // 敌对生物
        if (entity instanceof LivingEntity living) {
            return living instanceof RaiderEntity
                    || living instanceof PhantomEntity
                    || living instanceof VexEntity
                    || living instanceof HostileEntity
                    || living instanceof EnderDragonEntity
                    || living instanceof WitherEntity;
        }

        // 敌对弹射物：排除穿甲箭和友好生物的弹射物
        if (entity instanceof ProjectileEntity projectile) {
            if (entity instanceof ArmorPiercingArrowEntity) {
                return false;
            }

            Entity owner = projectile.getOwner();

            // 排除玩家发射的弹射物
            if (owner instanceof PlayerEntity) {
                return false;
            }

            // 排除友好生物发射的弹射物（雪傀儡、铁傀儡等）
            if (owner instanceof GolemEntity) {
                return false;
            }

            // 排除驯服的生物发射的弹射物
            if (owner instanceof TameableEntity tameable && tameable.isTamed()) {
                return false;
            }

            // 其他弹射物（凋灵之首、末影龙火球等）是敌对目标
            return true;
        }

        return false;
    }

    private boolean canPathfindTo(MobEntity pathTester, Entity target, ServerWorld world) {
        BlockPos targetPos = target.getBlockPos();

        // 弹射物：检查是否在合理位置（不在深地下）
        if (target instanceof ProjectileEntity) {
            return !isDeepUnderground(world, targetPos);
        }

        // 飞行生物：检查是否在合理位置
        if (target instanceof PhantomEntity || target instanceof VexEntity || target instanceof EnderDragonEntity) {
            return !isDeepUnderground(world, targetPos);
        }

        // 地面生物：简化验证，只检查是否在深地下
        // 不再使用寻路系统，因为它太严格且有性能问题
        if (target instanceof LivingEntity) {
            // 只要不在深地下，就认为可达（导弹有飞行能力，可以绕过障碍）
            return !isDeepUnderground(world, targetPos);
        }

        return false;
    }

    private boolean isDeepUnderground(ServerWorld world, BlockPos pos) {
        // 检查目标上方3格是否被完全封闭
        int solidBlocks = 0;
        for (int dy = 1; dy <= 3; dy++) {
            BlockPos checkPos = pos.up(dy);
            if (!world.getBlockState(checkPos).isAir()
                    && world.getBlockState(checkPos).isFullCube(world, checkPos)) {
                solidBlocks++;
            }
        }
        // 如果上方3格都是实心方块，认为在深地下
        return solidBlocks >= 3;
    }

    private double scoreTarget(Entity entity, Vec3d origin) {
        double score = entity.squaredDistanceTo(origin);
        // 优先级：灾厄村民 > 弹射物 > 飞行怪物
        if (entity instanceof RaiderEntity) score -= 500000.0d;
        if (entity instanceof ProjectileEntity) score -= 250000.0d;
        if (entity instanceof PhantomEntity || entity instanceof VexEntity) score -= 150000.0d;
        return score;
    }

    private boolean launch(ServerWorld world, BlockPos pos, Entity target) {
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = items.get(i);
            if (stack.isEmpty()) continue;

            BeeMissileEntity missile;
            if (stack.isOf(AutoEnchantsMod.STINGER_MISSILE_ITEM)) {
                StingerMissileEntity stinger = new StingerMissileEntity(AutoEnchantsMod.STINGER_MISSILE, world);
                stinger.setPiercingLevel(StingerMissileItem.getPiercingLevel(stack));
                missile = stinger;
            } else {
                missile = new BeeMissileEntity(AutoEnchantsMod.BEE_MISSILE, world);
            }

            Vec3d spawn = Vec3d.ofCenter(pos.up()).add(0.0d, 0.2d, 0.0d);
            missile.refreshPositionAndAngles(spawn.x, spawn.y, spawn.z, 0.0f, -90.0f);
            missile.setInitialDirection(new Vec3d(0.0d, 1.0d, 0.0d), LAUNCH_SPEED);
            missile.setIgnoreBlockCollisionTicks(LAUNCH_CLEARANCE_TICKS);
            // 设置优先目标：蜂巢垂发已验证该目标可达，导弹将直接追踪，跳过锥形搜索
            missile.setPriorityTarget(target);
            world.spawnEntity(missile);

            items.set(i, ItemStack.EMPTY);
            markDirty();
            syncLoadedState();
            world.playSound(null, pos, SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.BLOCKS, 1.0f, 1.2f);
            world.spawnParticles(ParticleTypes.CLOUD, spawn.x, spawn.y, spawn.z, 18, 0.18d, 0.12d, 0.18d, 0.04d);
            world.spawnParticles(ParticleTypes.LANDING_HONEY, spawn.x, spawn.y, spawn.z, 8, 0.18d, 0.12d, 0.18d, 0.01d);
            return true;
        }
        return false;
    }

    private void syncLoadedState() {
        if (world == null) return;
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BeeNestVlsBlock && state.get(BeeNestVlsBlock.LOADED) != hasMissile()) {
            world.setBlockState(pos, state.with(BeeNestVlsBlock.LOADED, hasMissile()), 3);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        items.clear();
        Inventories.readNbt(nbt, items);
        fireCooldown = nbt.getInt("FireCooldown");
        scanCooldown = nbt.getInt("ScanCooldown");
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        Inventories.writeNbt(nbt, items);
        nbt.putInt("FireCooldown", fireCooldown);
        nbt.putInt("ScanCooldown", scanCooldown);
    }
}
