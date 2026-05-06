package com.example.autoenchants.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 蜜蜂导弹实体：
 *  - 投掷后前 5 tick 沿玩家朝向直飞（弹道阶段）。
 *  - 之后开始制导：在飞行方向 40 度半角圆锥内寻敌（参考精确制导附魔），优先灾厄村民；可锁定飞行怪物。
 *  - 锁定后使用蜜蜂的 BeeFlyNavigation 寻路（继承自 BeeEntity），可绕开障碍物。
 *  - 飞行中持续产生轨迹粒子。
 *  - 撞击敌人后：2 级爆炸 + 大量粒子（参考反应装甲附魔）+ 范围中毒。
 */
public class BeeMissileEntity extends BeeEntity {

    private static final int PRE_GUIDANCE_TICKS = 5;
    private static final int MAX_LIFETIME_TICKS = 200;
    private static final double SEARCH_RANGE = 40.0d;
    /** 制导寻敌锥形半角（参考精确制导附魔 45°，按需求改为 40°）。 */
    private static final double CONE_HALF_ANGLE_DEG = 40.0d;
    /** 弹道阶段速度（每 tick 位移 ≈ 1.4 格）。 */
    private static final double BALLISTIC_SPEED = 1.4d;
    /** 盘旋半径。 */
    private static final double HOVER_RADIUS = 4.0d;
    /** 盘旋飞行速度乘数。 */
    private static final double HOVER_NAV_SPEED = 1.0d;
    /** 盘旋角速度（弧度/tick），约 6 秒一圈。 */
    private static final double HOVER_ANGULAR_SPEED = Math.PI / 60.0d;
    private static final int REPATH_INTERVAL = 6;
    /** 盘旋状态下为避免频繁重算路径的 “到达检查” 阈值。 */
    private static final double HOVER_WAYPOINT_REACH_SQ = 1.5d * 1.5d;
    /** 直接撞击碰撞箱外扩，避免高速穿透。 */
    private static final double COLLISION_INFLATE = 0.15d;
    private static final double POISON_RADIUS = 3.0d;
    private static final int POISON_DURATION_TICKS = 100;
    private static final int POISON_AMPLIFIER = 1;
    /** 爆炸威力（4 级，相当于 TNT）。 */
    private static final float EXPLOSION_POWER = 4.0f;

    @Nullable
    private UUID ownerUuid;
    @Nullable
    private UUID targetUuid;
    private Vec3d initialDir = Vec3d.ZERO;
    private Vec3d ballisticVelocity = Vec3d.ZERO;
    /** 自定义计时器，避免与父类 age 冲突。 */
    private int missileAge;
    private int repathCooldown;
    private boolean exploded;
    /** 盘旋锚点：为 null 表示当前不处于盘旋状态。 */
    @Nullable
    private Vec3d hoverAnchor;
    /** 盘旋当前角度（弧度）。 */
    private double hoverAngle;

    public BeeMissileEntity(EntityType<? extends BeeEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
        this.setSilent(true);
        this.setInvulnerable(true);
        this.setCanPickUpLoot(false);
        this.setPersistent();
        EntityAttributeInstance fly = this.getAttributeInstance(EntityAttributes.GENERIC_FLYING_SPEED);
        if (fly != null) fly.setBaseValue(0.8d);
        EntityAttributeInstance mov = this.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
        if (mov != null) mov.setBaseValue(0.6d);
    }

    @Override
    protected void initGoals() {
        // 必须调用 super.initGoals() 以初始化 BeeEntity.pollinateGoal 等字段，
        // 否则 BeeEntity 的自定义 BirdNavigation$1.tick() 会因 pollinateGoal == null 而 NPE 崩溃。
        // 初始化完毕后立即清空所有 Goal，蜜蜂的采蜜/回巢/攻击 AI 不会实际运行。
        super.initGoals();
        this.goalSelector.clear(g -> true);
        this.targetSelector.clear(g -> true);
    }

    @Override
    protected void mobTick() {
        // 跳过蜜蜂的愤怒/采蜜逻辑
    }

    public void setOwner(@Nullable PlayerEntity owner) {
        this.ownerUuid = owner != null ? owner.getUuid() : null;
    }

    @Nullable
    private Entity getOwnerEntity() {
        if (ownerUuid == null || !(getWorld() instanceof ServerWorld sw)) return null;
        return sw.getEntity(ownerUuid);
    }

    public void setInitialDirection(Vec3d look, double speed) {
        setInitialDirection(look, speed, Vec3d.ZERO);
    }

    public void setInitialDirection(Vec3d look, double speed, Vec3d inheritedVelocity) {
        if (look.lengthSquared() < 1.0E-6d) {
            this.initialDir = new Vec3d(0, 1, 0);
        } else {
            this.initialDir = look.normalize();
        }
        Vec3d v = this.initialDir.multiply(speed).add(inheritedVelocity);
        this.ballisticVelocity = v;
        this.setVelocity(v);
        this.velocityModified = true;
        // 朝向初速方向
        float yaw = (float) (MathHelper.atan2(v.z, v.x) * (180.0d / Math.PI)) - 90.0f;
        float pitch = (float) (MathHelper.atan2(v.y, v.horizontalLength()) * (180.0d / Math.PI));
        this.setYaw(yaw);
        this.setPitch(pitch);
        this.setBodyYaw(yaw);
        this.setHeadYaw(yaw);
    }

    @Override
    public void tick() {
        if (this.getWorld().isClient()) {
            super.tick();
            return;
        }

        missileAge++;
        if (missileAge > MAX_LIFETIME_TICKS) {
            explode();
            return; // 已 discard，避免对已移除实体调用 super.tick
        }

        // 保存 super.tick() 前的速度用于平滑转向（BeeMoveControl 会在 super.tick() 内修改速度）。
        Vec3d preTickVel = this.getVelocity();
        LivingEntity homingTarget = null;

        if (missileAge <= PRE_GUIDANCE_TICKS) {
            // 弹道阶段：禁用导航。
            this.getNavigation().stop();
        } else if (this.getWorld() instanceof ServerWorld sw) {
            homingTarget = resolveOrAcquireTarget(sw);
            if (homingTarget != null) {
                this.getNavigation().stop(); // 归航模式：直接控制速度矢量
            } else {
                tickHover(); // 盘旋模式：在 super.tick() 前设置导航目标
            }
        }

        spawnTrailParticles();

        // 父类 tick 处理 AI/Navigation/MoveControl/位移/方块碰撞标志位。
        super.tick();

        if (exploded || this.isRemoved()) return;

        // 速度矢量在 super.tick() 之后强制覆盖，防止 BeeEntity BeeMoveControl 干扰。
        if (missileAge <= PRE_GUIDANCE_TICKS) {
            this.setVelocity(ballisticVelocity.lengthSquared() > 1.0E-6d ? ballisticVelocity : initialDir.multiply(BALLISTIC_SPEED));
            this.velocityModified = true;
        } else if (homingTarget != null) {
            tickHoming(homingTarget, preTickVel);
        }
        // 盘旋模式：由 navigation 控制速度，无需手动设置。

        // 位移完成后再判定碰撞：撞墙必爆，撞到合法敌人也爆。
        if (this.horizontalCollision || this.verticalCollision) {
            explode();
        } else {
            checkEntityCollision();
        }
    }

    @Nullable
    private LivingEntity resolveOrAcquireTarget(ServerWorld sw) {
        LivingEntity target = resolveTarget(sw);
        if (target == null) {
            // 未锁定时：未盘旋 → 按发射方向锥形搜；已盘旋 → 全周搜。
            double halfAngle = (hoverAnchor == null) ? CONE_HALF_ANGLE_DEG : 180.0d;
            target = acquireTarget(sw, halfAngle);
            if (target != null) {
                targetUuid = target.getUuid();
                hoverAnchor = null; // 锁定后退出盘旋
            }
        }
        return target;
    }

    private void tickHoming(LivingEntity target, Vec3d preTickVel) {
        // super.tick() 之后调用：速度矢量直接设置，覆盖 BeeMoveControl 对速度的干扰。
        Vec3d toTarget = target.getPos()
                .add(0.0d, target.getHeight() * 0.5d, 0.0d)
                .subtract(this.getPos());
        double dist = toTarget.length();
        if (dist < 0.3d) {
            explode();
            return;
        }
        Vec3d desired = toTarget.normalize();
        // 用 super.tick() 前保存的速度作为平滑转向基准，避免 MoveControl 污染方向。
        Vec3d sum = preTickVel.lengthSquared() > 1.0E-6d
                ? preTickVel.normalize().add(desired) : desired;
        Vec3d steer = sum.lengthSquared() > 1.0E-6d ? sum.normalize() : desired;
        this.setVelocity(steer.multiply(BALLISTIC_SPEED));
        this.velocityModified = true;
        this.getLookControl().lookAt(target, 60.0f, 60.0f);
    }

    private void tickHover() {
        // 进入盘旋：以当前位置为锚点，起始角以当前速度方向的垂直方向为初值，避免一进入就倒退。
        if (hoverAnchor == null) {
            hoverAnchor = this.getPos();
            Vec3d v = this.getVelocity();
            // 水平平面内起始角：atan2(z, x)
            hoverAngle = (v.lengthSquared() > 1.0E-6d)
                    ? Math.atan2(v.z, v.x)
                    : Math.atan2(initialDir.z, initialDir.x);
            this.getNavigation().stop();
            repathCooldown = 0;
        }

        // 推进角度，计算下一路径点。到达路径点或超时后重算。
        hoverAngle += HOVER_ANGULAR_SPEED;
        double tx = hoverAnchor.x + Math.cos(hoverAngle) * HOVER_RADIUS;
        double ty = hoverAnchor.y;
        double tz = hoverAnchor.z + Math.sin(hoverAngle) * HOVER_RADIUS;

        boolean reached = this.squaredDistanceTo(tx, ty, tz) < HOVER_WAYPOINT_REACH_SQ;
        if (repathCooldown <= 0 || this.getNavigation().isIdle() || reached) {
            this.getNavigation().startMovingTo(tx, ty, tz, HOVER_NAV_SPEED);
            repathCooldown = REPATH_INTERVAL;
        } else {
            repathCooldown--;
        }
        // 朋向下一路径点，外观上顺着圆周朋向进行。
        this.getLookControl().lookAt(tx, ty + 0.5d, tz, 30.0f, 30.0f);
    }

    @Nullable
    private LivingEntity resolveTarget(ServerWorld sw) {
        if (targetUuid == null) return null;
        Entity e = sw.getEntity(targetUuid);
        if (e instanceof LivingEntity living && living.isAlive() && !living.isSpectator()) {
            return living;
        }
        targetUuid = null;
        return null;
    }

    @Nullable
    private LivingEntity acquireTarget(ServerWorld sw, double coneHalfAngleDeg) {
        // half-angle >= 180 表示全周搜，不需方向向量。
        boolean omnidirectional = coneHalfAngleDeg >= 180.0d - 1.0E-6d;
        // 以发射时的初始方向作为锥形轴：比实时速度矢量更可靠，不受 BeeMoveControl 干扰。
        Vec3d forward = initialDir.lengthSquared() > 1.0E-6d
                ? initialDir
                : (this.getVelocity().lengthSquared() > 1.0E-6d ? this.getVelocity().normalize() : Vec3d.ZERO);
        if (!omnidirectional && forward.lengthSquared() < 1.0E-6d) return null;

        double cosThreshold = Math.cos(Math.toRadians(coneHalfAngleDeg));
        double rangeSq = SEARCH_RANGE * SEARCH_RANGE;
        Entity owner = getOwnerEntity();

        Box box = this.getBoundingBox().expand(SEARCH_RANGE);
        List<LivingEntity> candidates = sw.getEntitiesByClass(
                LivingEntity.class,
                box,
                e -> e.isAlive() && !e.isSpectator() && e != owner && e != this && isValidTarget(e)
        );

        Vec3d origin = this.getPos();
        LivingEntity best = null;
        double bestScore = -Double.MAX_VALUE;

        for (LivingEntity c : candidates) {
            Vec3d toC = c.getPos().add(0.0d, c.getHeight() * 0.5d, 0.0d).subtract(origin);
            double distSq = toC.lengthSquared();
            if (distSq < 1.0E-4d || distSq > rangeSq) continue;
            double dist = Math.sqrt(distSq);
            double alignment = omnidirectional ? 1.0d : forward.dotProduct(toC.multiply(1.0d / dist));
            if (!omnidirectional && alignment < cosThreshold) continue;

            double score = alignment * 1000.0d - dist;
            // 优先级：灾厄村民
            if (c instanceof RaiderEntity) score += 5000.0d;
            // 飞行怪物次优先
            if (c instanceof PhantomEntity || c instanceof VexEntity) score += 1500.0d;

            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private boolean isValidTarget(LivingEntity e) {
        if (e instanceof RaiderEntity) return true;
        if (e instanceof PhantomEntity) return true;
        if (e instanceof VexEntity) return true;
        if (e instanceof HostileEntity) return true;
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity) return true;
        return false;
    }

    private boolean checkEntityCollision() {
        if (exploded) return false;
        Vec3d vel = this.getVelocity();
        Box probe = this.getBoundingBox().stretch(vel).expand(COLLISION_INFLATE);
        // 仅对合法敌人引爆，避免误炸玩家、村民、动物等无辜实体。
        List<Entity> entities = this.getWorld().getOtherEntities(this, probe,
                e -> e.isAlive()
                        && !(e instanceof ProjectileEntity)
                        && !(e instanceof BeeMissileEntity)
                        && !isOwner(e)
                        && e instanceof LivingEntity living
                        && isValidTarget(living));
        if (!entities.isEmpty()) {
            explode();
            return true;
        }
        return false;
    }

    private boolean isOwner(Entity e) {
        return ownerUuid != null && e.getUuid().equals(ownerUuid);
    }

    private void spawnTrailParticles() {
        if (!(this.getWorld() instanceof ServerWorld sw)) return;
        // 制导/锁定后产生更明显的轨迹粒子
        if (missileAge > PRE_GUIDANCE_TICKS) {
            sw.spawnParticles(ParticleTypes.FIREWORK, getX(), getBodyY(0.5d), getZ(),
                    2, 0.06d, 0.06d, 0.06d, 0.01d);
            sw.spawnParticles(ParticleTypes.LANDING_HONEY, getX(), getBodyY(0.5d), getZ(),
                    1, 0.05d, 0.05d, 0.05d, 0.0d);
            if (this.random.nextInt(2) == 0) {
                sw.spawnParticles(ParticleTypes.SMOKE, getX(), getBodyY(0.4d), getZ(),
                        1, 0.04d, 0.04d, 0.04d, 0.0d);
            }
        } else {
            sw.spawnParticles(ParticleTypes.POOF, getX(), getBodyY(0.5d), getZ(),
                    1, 0.05d, 0.05d, 0.05d, 0.0d);
        }
    }

    private void explode() {
        if (exploded || this.getWorld().isClient()) return;
        exploded = true;
        ServerWorld sw = (ServerWorld) this.getWorld();
        Entity owner = getOwnerEntity();

        // 爆炸前先收集中毒目标：爆炸冲击波会将实体推出范围，若在爆炸后再查询会导致距离检查失败。
        List<LivingEntity> poisonTargets = sw.getEntitiesByClass(
                LivingEntity.class,
                new Box(getX() - POISON_RADIUS, getY() - POISON_RADIUS, getZ() - POISON_RADIUS,
                        getX() + POISON_RADIUS, getY() + POISON_RADIUS, getZ() + POISON_RADIUS),
                e -> e.isAlive()
                        && !e.isSpectator()
                        && e != owner
                        && e != this
                        && isValidTarget(e)
                        && e.squaredDistanceTo(this) <= POISON_RADIUS * POISON_RADIUS
        );

        // 爆炸
        this.getWorld().createExplosion(
                owner != null ? owner : this,
                getX(), getY(), getZ(),
                EXPLOSION_POWER, false,
                World.ExplosionSourceType.MOB
        );

        // 大量粒子（参考反应装甲附魔）
        sw.spawnParticles(ParticleTypes.EXPLOSION, getX(), getY(), getZ(), 2, 0.3d, 0.3d, 0.3d, 0.01d);
        sw.spawnParticles(ParticleTypes.GLOW_SQUID_INK, getX(), getY(), getZ(), 28, 0.45d, 0.35d, 0.45d, 0.02d);
        sw.spawnParticles(ParticleTypes.LANDING_HONEY, getX(), getY(), getZ(), 22, 0.55d, 0.35d, 0.55d, 0.01d);
        sw.spawnParticles(ParticleTypes.LAVA, getX(), getY(), getZ(), 10, 0.42d, 0.25d, 0.42d, 0.01d);
        sw.spawnParticles(ParticleTypes.FLAME, getX(), getY(), getZ(), 18, 0.5d, 0.35d, 0.5d, 0.03d);
        sw.spawnParticles(ParticleTypes.SMOKE, getX(), getY(), getZ(), 16, 0.5d, 0.4d, 0.5d, 0.04d);

        for (LivingEntity target : poisonTargets) {
            if (target.isAlive()) {
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, POISON_DURATION_TICKS, POISON_AMPLIFIER), owner);
            }
        }

        this.discard();
    }

    // ==================== Damage / persistence ====================

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false; // 完全免疫伤害，仅靠寿命/碰撞终止
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushAway(Entity entity) {
        // 不被推开
    }

    @Override
    public boolean canHaveStatusEffect(StatusEffectInstance effect) {
        return false;
    }

    @Override
    public boolean cannotDespawn() {
        return true;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("MissileAge", missileAge);
        nbt.putDouble("InitDirX", initialDir.x);
        nbt.putDouble("InitDirY", initialDir.y);
        nbt.putDouble("InitDirZ", initialDir.z);
        nbt.putDouble("BallisticVelX", ballisticVelocity.x);
        nbt.putDouble("BallisticVelY", ballisticVelocity.y);
        nbt.putDouble("BallisticVelZ", ballisticVelocity.z);
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
        if (targetUuid != null) nbt.putUuid("Target", targetUuid);
        if (hoverAnchor != null) {
            nbt.putDouble("HoverX", hoverAnchor.x);
            nbt.putDouble("HoverY", hoverAnchor.y);
            nbt.putDouble("HoverZ", hoverAnchor.z);
            nbt.putDouble("HoverAngle", hoverAngle);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        missileAge = nbt.getInt("MissileAge");
        initialDir = new Vec3d(nbt.getDouble("InitDirX"), nbt.getDouble("InitDirY"), nbt.getDouble("InitDirZ"));
        ballisticVelocity = new Vec3d(nbt.getDouble("BallisticVelX"), nbt.getDouble("BallisticVelY"), nbt.getDouble("BallisticVelZ"));
        if (nbt.containsUuid("Owner")) ownerUuid = nbt.getUuid("Owner");
        if (nbt.containsUuid("Target")) targetUuid = nbt.getUuid("Target");
        if (nbt.contains("HoverX")) {
            hoverAnchor = new Vec3d(nbt.getDouble("HoverX"), nbt.getDouble("HoverY"), nbt.getDouble("HoverZ"));
            hoverAngle = nbt.getDouble("HoverAngle");
        }
    }
}
