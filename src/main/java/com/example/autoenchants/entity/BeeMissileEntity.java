package com.example.autoenchants.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.entity.ai.pathing.Path;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.BeeEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
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
    protected enum DetonationCause {
        IMPACT,
        EXPIRED
    }

    private static final int PRE_GUIDANCE_TICKS = 5;
    private static final int MAX_LIFETIME_TICKS = 200;
    private static final double SEARCH_RANGE = 80.0d;
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
    private int ignoreBlockCollisionTicks;
    @Nullable
    private Vec3d detonationDirectionOverride;
    /** 盘旋锚点：为 null 表示当前不处于盘旋状态。 */
    @Nullable
    private Vec3d hoverAnchor;
    /** 盘旋当前角度（弧度）。 */
    private double hoverAngle;
    /** 上一帧的视线方向，用于比例导引律计算。 */
    @Nullable
    private Vec3d previousLOS;
    /** 上一帧的目标位置，用于计算目标速度。 */
    @Nullable
    private Vec3d previousTargetPos;
    /** 导航比（比例导引律参数），通常取 3-5。 */
    private static final double NAVIGATION_CONSTANT = 4.0d;
    /** 最大转向角速度限制（弧度/tick），防止过度转向。 */
    private static final double MAX_TURN_RATE = Math.toRadians(15.0d);

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
    protected Entity getOwnerEntity() {
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

    public void setPriorityTarget(@Nullable Entity target) {
        this.targetUuid = target != null ? target.getUuid() : null;
        if (target != null) {
            this.hoverAnchor = null;
            // 重置比例导引状态
            this.previousLOS = null;
            this.previousTargetPos = null;
        }
    }

    public void setIgnoreBlockCollisionTicks(int ticks) {
        this.ignoreBlockCollisionTicks = Math.max(0, ticks);
    }

    @Override
    public void tick() {
        if (this.getWorld().isClient()) {
            super.tick();
            return;
        }

        missileAge++;
        if (missileAge > MAX_LIFETIME_TICKS) {
            explode(DetonationCause.EXPIRED);
            return; // 已 discard，避免对已移除实体调用 super.tick
        }

        // 保存 super.tick() 前的速度用于平滑转向（BeeMoveControl 会在 super.tick() 内修改速度）。
        Vec3d preTickVel = this.getVelocity();
        Entity homingTarget = null;

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
        if ((this.horizontalCollision || this.verticalCollision) && ignoreBlockCollisionTicks <= 0) {
            explode();
        } else {
            checkEntityCollision();
        }
        if (ignoreBlockCollisionTicks > 0) {
            ignoreBlockCollisionTicks--;
        }
    }

    @Nullable
    private Entity resolveOrAcquireTarget(ServerWorld sw) {
        Entity target = resolveTarget(sw);
        if (target == null) {
            // 未锁定时：未盘旋 → 按发射方向锥形搜；已盘旋 → 全周搜。
            double halfAngle = (hoverAnchor == null) ? CONE_HALF_ANGLE_DEG : 180.0d;
            target = acquireTarget(sw, halfAngle);
            if (target != null) {
                targetUuid = target.getUuid();
                hoverAnchor = null; // 锁定后退出盘旋
                // 重置比例导引状态
                previousLOS = null;
                previousTargetPos = null;
            }
        }
        return target;
    }

    private void tickHoming(Entity target, Vec3d preTickVel) {
        // super.tick() 之后调用：速度矢量直接设置，覆盖 BeeMoveControl 对速度的干扰。
        Vec3d targetCenter = target.getPos().add(0.0d, target.getHeight() * 0.5d, 0.0d);
        Vec3d missilePos = this.getPos();
        Vec3d toTarget = targetCenter.subtract(missilePos);
        double dist = toTarget.length();

        if (dist < 0.3d) {
            explodeToward(toTarget);
            return;
        }

        // 如果目标是弹射物，使用比例导引律
        if (target instanceof ProjectileEntity) {
            Vec3d newVel = applyProportionalNavigation(target, targetCenter, missilePos, preTickVel, dist);
            this.setVelocity(newVel);
            this.velocityModified = true;
        } else {
            // 对于生物目标，使用原有的简单追踪逻辑
            Vec3d desired = toTarget.normalize();
            Vec3d sum = preTickVel.lengthSquared() > 1.0E-6d
                    ? preTickVel.normalize().add(desired) : desired;
            Vec3d steer = sum.lengthSquared() > 1.0E-6d ? sum.normalize() : desired;
            this.setVelocity(steer.multiply(BALLISTIC_SPEED));
            this.velocityModified = true;
        }

        this.getLookControl().lookAt(target, 60.0f, 60.0f);
    }

    private Vec3d applyProportionalNavigation(Entity target, Vec3d targetCenter, Vec3d missilePos, Vec3d preTickVel, double dist) {
        // 计算目标速度
        Vec3d targetVelocity = Vec3d.ZERO;
        if (previousTargetPos != null) {
            targetVelocity = targetCenter.subtract(previousTargetPos);
        } else if (target instanceof ProjectileEntity) {
            targetVelocity = target.getVelocity();
        }
        previousTargetPos = targetCenter;

        // 当前速度
        Vec3d currentVel = preTickVel.lengthSquared() > 1.0E-6d ? preTickVel : this.getVelocity();
        if (currentVel.lengthSquared() < 1.0E-6d) {
            currentVel = targetCenter.subtract(missilePos).normalize().multiply(BALLISTIC_SPEED);
        }

        // 预测拦截点（简化的比例导引）
        Vec3d interceptPoint = calculateInterceptPoint(missilePos, currentVel.length(), targetCenter, targetVelocity);

        // 指向拦截点的方向
        Vec3d toIntercept = interceptPoint.subtract(missilePos);
        double interceptDist = toIntercept.length();

        if (interceptDist < 0.3d) {
            return currentVel.normalize().multiply(BALLISTIC_SPEED);
        }

        Vec3d desiredDir = toIntercept.normalize();
        Vec3d currentDir = currentVel.normalize();

        // 计算转向向量
        Vec3d steering = desiredDir.subtract(currentDir);
        double steerMag = steering.length();

        // 应用转向限制
        if (steerMag > MAX_TURN_RATE) {
            steering = steering.normalize().multiply(MAX_TURN_RATE);
        }

        // 新方向 = 当前方向 + 转向
        Vec3d newDir = currentDir.add(steering);
        if (newDir.lengthSquared() < 1.0E-6d) {
            newDir = desiredDir;
        } else {
            newDir = newDir.normalize();
        }

        // 近距离增强直接追踪
        if (dist < 5.0d) {
            Vec3d directDir = targetCenter.subtract(missilePos).normalize();
            double blendFactor = dist / 5.0d; // 0格时=0（全直接），5格时=1（全预测）
            newDir = newDir.multiply(blendFactor).add(directDir.multiply(1.0d - blendFactor));
            newDir = newDir.normalize();
        }

        return newDir.multiply(BALLISTIC_SPEED);
    }

    /**
     * 计算拦截点：求解导弹和目标相遇的位置
     */
    private Vec3d calculateInterceptPoint(Vec3d missilePos, double missileSpeed, Vec3d targetPos, Vec3d targetVel) {
        // 如果目标静止或速度很慢，直接返回目标当前位置
        double targetSpeed = targetVel.length();
        if (targetSpeed < 0.01d) {
            return targetPos;
        }

        // 求解拦截时间（一元二次方程）
        Vec3d toTarget = targetPos.subtract(missilePos);
        double a = targetVel.dotProduct(targetVel) - missileSpeed * missileSpeed;
        double b = 2.0d * toTarget.dotProduct(targetVel);
        double c = toTarget.dotProduct(toTarget);

        double discriminant = b * b - 4.0d * a * c;

        // 无解或目标太快，返回前置预测点
        if (Math.abs(a) < 1.0E-6d || discriminant < 0.0d) {
            // 简单预测：假设固定时间后的目标位置
            double predictTime = toTarget.length() / missileSpeed;
            return targetPos.add(targetVel.multiply(predictTime));
        }

        // 取较小的正根（最近的拦截时间）
        double t1 = (-b + Math.sqrt(discriminant)) / (2.0d * a);
        double t2 = (-b - Math.sqrt(discriminant)) / (2.0d * a);

        double interceptTime;
        if (t1 > 0.0d && t2 > 0.0d) {
            interceptTime = Math.min(t1, t2);
        } else if (t1 > 0.0d) {
            interceptTime = t1;
        } else if (t2 > 0.0d) {
            interceptTime = t2;
        } else {
            // 无正解，使用简单预测
            interceptTime = toTarget.length() / missileSpeed;
        }

        // 限制预测时间，避免过度超前
        interceptTime = Math.min(interceptTime, 40.0d); // 最多预测40 tick

        return targetPos.add(targetVel.multiply(interceptTime));
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
    private Entity resolveTarget(ServerWorld sw) {
        if (targetUuid == null) return null;
        Entity e = sw.getEntity(targetUuid);
        if (isValidTargetEntity(e)) {
            return e;
        }
        targetUuid = null;
        return null;
    }

    @Nullable
    private Entity acquireTarget(ServerWorld sw, double coneHalfAngleDeg) {
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
        List<Entity> candidates = sw.getOtherEntities(
                this,
                box,
                e -> e != owner && isValidTargetEntity(e)
        );

        Vec3d origin = this.getPos();
        Entity best = null;
        double bestScore = -Double.MAX_VALUE;

        for (Entity c : candidates) {
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
            if (isValidProjectileTarget(c)) score += 2500.0d;

            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    protected boolean isValidTarget(LivingEntity e) {
        if (e instanceof RaiderEntity) return true;
        if (e instanceof PhantomEntity) return true;
        if (e instanceof VexEntity) return true;
        if (e instanceof HostileEntity) return true;
        if (e instanceof EnderDragonEntity || e instanceof WitherEntity) return true;
        return false;
    }

    protected boolean isValidTargetEntity(@Nullable Entity e) {
        if (e == null || !e.isAlive() || e.isSpectator() || e == this || isOwner(e)) return false;
        if (e instanceof LivingEntity living) return isValidTarget(living);
        return isValidProjectileTarget(e);
    }

    protected boolean isValidProjectileTarget(Entity e) {
        if (!(e instanceof ProjectileEntity)) return false;
        if (e instanceof BeeMissileEntity || e instanceof ArmorPiercingArrowEntity) return false;

        Entity projectileOwner = ((ProjectileEntity) e).getOwner();

        // 排除玩家自己发射的弹射物
        if (isOwner(projectileOwner)) return false;

        // 排除友好生物发射的弹射物（雪傀儡、铁傀儡等）
        if (projectileOwner instanceof GolemEntity) return false;

        // 排除玩家发射的弹射物
        if (projectileOwner instanceof PlayerEntity) return false;

        // 排除驯服的生物发射的弹射物
        if (projectileOwner instanceof TameableEntity tameable && tameable.isTamed()) return false;

        // 其他情况：如果没有发射者或发射者是敌对生物，则视为有效目标
        return true;
    }

    private boolean checkEntityCollision() {
        if (exploded) return false;
        Vec3d vel = this.getVelocity();
        Box probe = this.getBoundingBox().stretch(vel).expand(COLLISION_INFLATE);
        // 仅对合法敌人引爆，避免误炸玩家、村民、动物等无辜实体。
        List<Entity> entities = this.getWorld().getOtherEntities(this, probe,
                e -> e.isAlive()
                        && !(e instanceof BeeMissileEntity)
                        && !isOwner(e)
                        && isValidTargetEntity(e));
        if (!entities.isEmpty()) {
            Entity hit = entities.get(0);
            Vec3d origin = this.getPos().add(0.0d, this.getHeight() * 0.5d, 0.0d);
            Vec3d targetCenter = hit.getPos().add(0.0d, hit.getHeight() * 0.5d, 0.0d);
            explodeToward(targetCenter.subtract(origin));
            return true;
        }
        return false;
    }

    private boolean isOwner(Entity e) {
        return e != null && ownerUuid != null && e.getUuid().equals(ownerUuid);
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

    protected Vec3d getForwardDirection() {
        if (detonationDirectionOverride != null && detonationDirectionOverride.lengthSquared() > 1.0E-6d) {
            return detonationDirectionOverride.normalize();
        }
        Vec3d velocity = this.getVelocity();
        if (velocity.lengthSquared() > 1.0E-6d) {
            return velocity.normalize();
        }
        if (initialDir.lengthSquared() > 1.0E-6d) {
            return initialDir.normalize();
        }
        return this.getRotationVec(1.0f).normalize();
    }

    protected void explode() {
        explode(DetonationCause.IMPACT);
    }

    protected void explodeToward(Vec3d direction) {
        if (direction.lengthSquared() > 1.0E-6d) {
            detonationDirectionOverride = direction.normalize();
        }
        explode(DetonationCause.IMPACT);
    }

    protected void explode(DetonationCause cause) {
        if (exploded || this.getWorld().isClient()) return;
        exploded = true;
        ServerWorld sw = (ServerWorld) this.getWorld();
        Entity owner = getOwnerEntity();
        onDetonate(sw, owner, cause);
        this.discard();
    }

    protected void onDetonate(ServerWorld sw, @Nullable Entity owner, DetonationCause cause) {
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

        // 收集并摧毁范围内的敌对弹射物（爆炸伤害可能无法摧毁特殊弹射物如凋灵之首）
        double projectileKillRadius = EXPLOSION_POWER * 2.0; // 爆炸范围
        List<Entity> projectileTargets = sw.getOtherEntities(
                this,
                new Box(getX() - projectileKillRadius, getY() - projectileKillRadius, getZ() - projectileKillRadius,
                        getX() + projectileKillRadius, getY() + projectileKillRadius, getZ() + projectileKillRadius),
                e -> e.isAlive()
                        && !e.isSpectator()
                        && e.squaredDistanceTo(this) <= projectileKillRadius * projectileKillRadius
                        && isValidProjectileTarget(e)
        );

        // 爆炸
        this.getWorld().createExplosion(
                owner != null ? owner : this,
                getX(), getY(), getZ(),
                EXPLOSION_POWER, false,
                World.ExplosionSourceType.MOB
        );

        // 主动摧毁弹射物（在爆炸后，确保它们被移除）
        for (Entity projectile : projectileTargets) {
            if (projectile.isAlive()) {
                projectile.discard(); // 直接移除弹射物
            }
        }

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
        nbt.putInt("IgnoreBlockCollisionTicks", ignoreBlockCollisionTicks);
        if (hoverAnchor != null) {
            nbt.putDouble("HoverX", hoverAnchor.x);
            nbt.putDouble("HoverY", hoverAnchor.y);
            nbt.putDouble("HoverZ", hoverAnchor.z);
            nbt.putDouble("HoverAngle", hoverAngle);
        }
        if (previousLOS != null) {
            nbt.putDouble("PrevLOSX", previousLOS.x);
            nbt.putDouble("PrevLOSY", previousLOS.y);
            nbt.putDouble("PrevLOSZ", previousLOS.z);
        }
        if (previousTargetPos != null) {
            nbt.putDouble("PrevTargetX", previousTargetPos.x);
            nbt.putDouble("PrevTargetY", previousTargetPos.y);
            nbt.putDouble("PrevTargetZ", previousTargetPos.z);
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
        ignoreBlockCollisionTicks = nbt.getInt("IgnoreBlockCollisionTicks");
        if (nbt.contains("HoverX")) {
            hoverAnchor = new Vec3d(nbt.getDouble("HoverX"), nbt.getDouble("HoverY"), nbt.getDouble("HoverZ"));
            hoverAngle = nbt.getDouble("HoverAngle");
        }
        if (nbt.contains("PrevLOSX")) {
            previousLOS = new Vec3d(nbt.getDouble("PrevLOSX"), nbt.getDouble("PrevLOSY"), nbt.getDouble("PrevLOSZ"));
        }
        if (nbt.contains("PrevTargetX")) {
            previousTargetPos = new Vec3d(nbt.getDouble("PrevTargetX"), nbt.getDouble("PrevTargetY"), nbt.getDouble("PrevTargetZ"));
        }
    }
}
