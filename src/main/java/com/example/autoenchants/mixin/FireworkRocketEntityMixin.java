package com.example.autoenchants.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.projectile.ShulkerBulletEntity;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.List;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketEntityMixin {
    private static final String BLAST_FIREWORK_TAG_PREFIX = "autoenchants_blast_firework_lv_";
    private static final String FIREWORK_SHULKER_TAG_PREFIX = "autoenchants_firework_shulker_lv_";
    private static final String FIREWORK_GOLEM_TAG = "autoenchants_firework_golem";
    private static final String SHULKER_BULLET_FX_TAG = "autoenchants_firework_shulker_bullet_fx";
    private static final String BLAST_LAUNCHED_TNT_TAG = "autoenchants_blast_launched_tnt";
    private static final String BLAST_LAUNCHED_CREEPER_TAG = "autoenchants_blast_launched_creeper";

    @Inject(method = "onEntityHit", at = @At("HEAD"))
    private void autoenchants$blastOnEntityHit(EntityHitResult hitResult, CallbackInfo ci) {
        autoenchants$handleTaggedHit();
    }

    @Inject(method = "onBlockHit", at = @At("HEAD"))
    private void autoenchants$blastOnBlockHit(BlockHitResult hitResult, CallbackInfo ci) {
        autoenchants$handleTaggedHit();
    }

    @Inject(method = "explode", at = @At("HEAD"))
    private void autoenchants$spawnShulkerOnVanillaExplosion(CallbackInfo ci) {
        autoenchants$spawnShulkerBulletsIfTagged();
    }

    private void autoenchants$detonateBlastIfTagged() {
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient()) {
            return;
        }
        int blastLevel = autoenchants$findTagLevel(self, BLAST_FIREWORK_TAG_PREFIX);

        if (blastLevel <= 0) {
            return;
        }

        World world = self.getWorld();
        float power = 2.0f + blastLevel;
        world.createExplosion(self, self.getX(), self.getY(), self.getZ(), power, World.ExplosionSourceType.TNT);
        int chainCount = autoenchants$triggerSympatheticDetonation(world, self, blastLevel);
        if (chainCount > 0) {
            autoenchants$spawnBlastWaves(world, self, blastLevel, chainCount / 2);
        }
        autoenchants$spawnShulkerBulletsIfTagged();
        autoenchants$spawnGolemIfTagged();
        self.getCommandTags().removeIf(tag ->
                tag.startsWith(BLAST_FIREWORK_TAG_PREFIX)
                        || tag.startsWith(FIREWORK_SHULKER_TAG_PREFIX)
                        || tag.equals(FIREWORK_GOLEM_TAG));
        self.discard();
    }

    private void autoenchants$handleTaggedHit() {
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient()) {
            return;
        }

        int blastLevel = autoenchants$findTagLevel(self, BLAST_FIREWORK_TAG_PREFIX);
        if (blastLevel > 0) {
            autoenchants$detonateBlastIfTagged();
            return;
        }

        int shulkerLevel = autoenchants$findTagLevel(self, FIREWORK_SHULKER_TAG_PREFIX);
        if (shulkerLevel > 0) {
            autoenchants$spawnShulkerBulletsIfTagged();
            autoenchants$spawnGolemIfTagged();
            self.discard();
            return;
        }

        if (self.getCommandTags().contains(FIREWORK_GOLEM_TAG)) {
            autoenchants$spawnGolemIfTagged();
            self.discard();
        }
    }

    private void autoenchants$spawnShulkerBulletsIfTagged() {
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient()) {
            return;
        }

        int shulkerLevel = autoenchants$findTagLevel(self, FIREWORK_SHULKER_TAG_PREFIX);
        if (shulkerLevel <= 0) {
            return;
        }

        Entity ownerEntity = ((FireworkRocketEntity) (Object) this).getOwner();
        if (!(ownerEntity instanceof LivingEntity owner)) {
            return;
        }

        World world = self.getWorld();
        List<HostileEntity> targets = world.getEntitiesByClass(
                HostileEntity.class,
                self.getBoundingBox().expand(24.0d),
                target -> target.isAlive() && !target.isSpectator()
        );
        if (targets.isEmpty()) {
            return;
        }

        targets.sort(Comparator.comparingDouble(self::squaredDistanceTo));
        int bulletCount = 5 + shulkerLevel;
        autoenchants$spawnShulkerParticles(world, self.getX(), self.getY(), self.getZ(), bulletCount);
        for (int i = 0; i < bulletCount; i++) {
            HostileEntity target = targets.get(i % targets.size());
            ShulkerBulletEntity bullet = new ShulkerBulletEntity(world, owner, target, autoenchants$randomAxis(world));
            bullet.addCommandTag(SHULKER_BULLET_FX_TAG);
            bullet.refreshPositionAndAngles(self.getX(), self.getY(), self.getZ(), owner.getYaw(), owner.getPitch());
            world.spawnEntity(bullet);
        }

        self.getCommandTags().removeIf(tag -> tag.startsWith(FIREWORK_SHULKER_TAG_PREFIX));
    }

    private void autoenchants$spawnGolemIfTagged() {
        Entity self = (Entity) (Object) this;
        if (self.getWorld().isClient() || !self.getCommandTags().contains(FIREWORK_GOLEM_TAG)) {
            return;
        }

        IronGolemEntity golem = EntityType.IRON_GOLEM.create(self.getWorld());
        if (golem == null) {
            return;
        }
        golem.refreshPositionAndAngles(self.getX(), self.getY(), self.getZ(), self.getYaw(), self.getPitch());
        self.getWorld().spawnEntity(golem);
        autoenchants$spawnGolemParticles(self.getWorld(), golem.getX(), golem.getY(), golem.getZ());
        self.removeCommandTag(FIREWORK_GOLEM_TAG);
    }

    private int autoenchants$triggerSympatheticDetonation(World world, Entity source, int blastLevel) {
        int triggered = 0;
        int radius = 4 + blastLevel * 2;

        Box area = source.getBoundingBox().expand(radius);

        List<TntEntity> tntEntities = world.getEntitiesByClass(
                TntEntity.class,
                area,
                entity -> entity.isAlive() && entity != source
        );
        for (TntEntity tnt : tntEntities) {
            autoenchants$spawnFlameTrail(world, source.getPos(), tnt.getPos(), 24);
            autoenchants$launchEntityFromSource(source, tnt, BLAST_LAUNCHED_TNT_TAG, 1.35d + blastLevel * 0.12d);
            triggered++;
        }

        List<CreeperEntity> creepers = world.getEntitiesByClass(
                CreeperEntity.class,
                area,
                entity -> entity.isAlive() && entity != source
        );
        for (CreeperEntity creeper : creepers) {
            autoenchants$spawnFlameTrail(world, source.getPos(), creeper.getPos(), 20);
            autoenchants$launchEntityFromSource(source, creeper, BLAST_LAUNCHED_CREEPER_TAG, 1.20d + blastLevel * 0.1d);
            triggered++;
        }

        BlockPos center = BlockPos.ofFloored(source.getX(), source.getY(), source.getZ());
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.add(dx, dy, dz);
                    if (!world.getBlockState(pos).isOf(Blocks.TNT)) {
                        continue;
                    }
                    autoenchants$spawnFlameTrail(
                            world,
                            source.getPos(),
                            new Vec3d(pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d),
                            18
                    );
                    world.removeBlock(pos, false);
                    TntEntity launchedTnt = new TntEntity(
                            world,
                            pos.getX() + 0.5d,
                            pos.getY() + 0.5d,
                            pos.getZ() + 0.5d,
                            source instanceof LivingEntity living ? living : null
                    );
                    launchedTnt.setFuse(80);
                    autoenchants$launchEntityFromSource(source, launchedTnt, BLAST_LAUNCHED_TNT_TAG, 1.25d + blastLevel * 0.1d);
                    world.spawnEntity(launchedTnt);
                    triggered++;
                }
            }
        }

        return triggered;
    }

    private void autoenchants$launchEntityFromSource(Entity source, Entity target, String tag, double speed) {
        Vec3d direction = target.getPos().subtract(source.getPos());
        if (direction.lengthSquared() < 1.0E-5d) {
            direction = new Vec3d(
                    source.getWorld().random.nextDouble() - 0.5d,
                    0.0d,
                    source.getWorld().random.nextDouble() - 0.5d
            );
        }
        direction = direction.normalize();
        double upward = 0.45d + source.getWorld().random.nextDouble() * 0.25d;
        Vec3d velocity = new Vec3d(direction.x * speed, upward, direction.z * speed);
        target.setVelocity(velocity);
        target.velocityModified = true;
        target.addCommandTag(tag);
    }

    private void autoenchants$spawnBlastWaves(World world, Entity source, int blastLevel, int chainCount) {
        int waveCount = 8 + blastLevel * 2 + chainCount / 2;
        int maxWaves = Math.min(120, waveCount);
        double radius = 8.0d + blastLevel * 3.0d + chainCount * 0.4d;
        for (int i = 0; i < maxWaves; i++) {
            double offsetX = (world.random.nextDouble() * 2.0d - 1.0d) * radius;
            double offsetY = (world.random.nextDouble() * 2.0d - 1.0d) * (radius * 0.5d);
            double offsetZ = (world.random.nextDouble() * 2.0d - 1.0d) * radius;
            double x = source.getX() + offsetX;
            double y = source.getY() + offsetY;
            double z = source.getZ() + offsetZ;
            float wavePower = 2.5f + blastLevel * 0.45f + world.random.nextFloat() * 2.0f;
            world.createExplosion(source, x, y, z, wavePower, World.ExplosionSourceType.TNT);
        }
    }

    private void autoenchants$spawnFlameTrail(World world, Vec3d from, Vec3d to, int points) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        int safePoints = Math.max(6, points);
        Vec3d delta = to.subtract(from);
        for (int i = 0; i <= safePoints; i++) {
            double t = (double) i / (double) safePoints;
            double x = from.x + delta.x * t;
            double y = from.y + delta.y * t;
            double z = from.z + delta.z * t;
            serverWorld.spawnParticles(ParticleTypes.FLAME, x, y + 0.1d, z, 2, 0.06d, 0.06d, 0.06d, 0.001d);
        }
    }

    private void autoenchants$spawnShulkerParticles(World world, double x, double y, double z, int bulletCount) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        int amount = Math.min(60, 18 + bulletCount * 3);
        serverWorld.spawnParticles(ParticleTypes.END_ROD, x, y + 0.3d, z, amount, 0.6d, 0.45d, 0.6d, 0.05d);
        serverWorld.spawnParticles(ParticleTypes.DRAGON_BREATH, x, y + 0.2d, z, amount / 2, 0.45d, 0.3d, 0.45d, 0.02d);
    }

    private void autoenchants$spawnGolemParticles(World world, double x, double y, double z) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        serverWorld.spawnParticles(ParticleTypes.CLOUD, x, y + 0.2d, z, 25, 0.7d, 0.25d, 0.7d, 0.02d);
        serverWorld.spawnParticles(ParticleTypes.POOF, x, y + 1.0d, z, 30, 0.45d, 0.6d, 0.45d, 0.02d);
    }

    private int autoenchants$findTagLevel(Entity self, String prefix) {
        int level = 0;
        for (String tag : self.getCommandTags()) {
            if (!tag.startsWith(prefix)) {
                continue;
            }
            try {
                level = Integer.parseInt(tag.substring(prefix.length()));
                break;
            } catch (NumberFormatException ignored) {
                // Ignore malformed tags from external sources.
            }
        }
        return level;
    }

    private Direction.Axis autoenchants$randomAxis(World world) {
        return switch (world.random.nextInt(3)) {
            case 0 -> Direction.Axis.X;
            case 1 -> Direction.Axis.Y;
            default -> Direction.Axis.Z;
        };
    }
}
