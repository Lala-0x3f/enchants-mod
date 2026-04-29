package com.example.autoenchants.entity;

import com.example.autoenchants.AutoEnchantsMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Armor-piercing arrow.
 * Speed (m/s) = velocity_blocks_per_tick * 20.
 * Energy = speed_ms^2 (mass treated as 1).
 *
 * Calibration: a 60 m/s shot can break Blast Resistance 0.3 blocks.
 *   => break_threshold(energy) = blast_resistance * 12000.
 */
public class ArmorPiercingArrowEntity extends PersistentProjectileEntity {

    /** Energy cost per unit blast resistance (chosen so 60 m/s breaks BR 0.3). */
    private static final double BLOCK_ENERGY_PER_BR = 12000.0d;
    /** Energy cost to penetrate one HP point of a target. */
    private static final double ENTITY_ENERGY_PER_HP = 400.0d;
    /** Speed (m/s) above which the arrow shatters when it cannot break a block. */
    private static final double SHATTER_SPEED_MS = 200.0d;

    /** Track entities already pierced so we don't damage them every subsequent tick. */
    private final Set<UUID> piercedEntities = new HashSet<>();

    public ArmorPiercingArrowEntity(EntityType<? extends ArmorPiercingArrowEntity> type, World world) {
        super(type, world);
    }

    public ArmorPiercingArrowEntity(World world, LivingEntity shooter) {
        super(AutoEnchantsMod.ARMOR_PIERCING_ARROW, shooter, world);
    }

    public ArmorPiercingArrowEntity(World world, double x, double y, double z) {
        super(AutoEnchantsMod.ARMOR_PIERCING_ARROW, x, y, z, world);
    }

    @Override
    protected ItemStack asItemStack() {
        return new ItemStack(AutoEnchantsMod.ARMOR_PIERCING_ARROW_ITEM);
    }

    @Override
    protected SoundEvent getHitSound() {
        return SoundEvents.ITEM_TRIDENT_HIT_GROUND;
    }

    @Override
    protected boolean canHit(Entity entity) {
        return super.canHit(entity) && !piercedEntities.contains(entity.getUuid());
    }

    private double speedBlocksPerTick() {
        return getVelocity().length();
    }

    private double energy() {
        double sms = speedBlocksPerTick() * 20.0d;
        return sms * sms;
    }

    private void setEnergy(double newEnergy, Vec3d directionHint) {
        Vec3d dir = getVelocity();
        if (dir.lengthSquared() < 1.0E-8d) {
            dir = directionHint;
        }
        if (dir == null || dir.lengthSquared() < 1.0E-8d) {
            setVelocity(Vec3d.ZERO);
            return;
        }
        if (newEnergy <= 0.0d) {
            setVelocity(Vec3d.ZERO);
            return;
        }
        double newSpeedMs = Math.sqrt(newEnergy);
        double newBpt = newSpeedMs / 20.0d;
        setVelocity(dir.normalize().multiply(newBpt));
        velocityModified = true;
    }

    @Override
    protected void onEntityHit(EntityHitResult result) {
        Entity hit = result.getEntity();
        if (!(hit instanceof LivingEntity living)) {
            super.onEntityHit(result);
            return;
        }
        if (piercedEntities.contains(living.getUuid())) {
            return;
        }

        double energy = energy();
        Vec3d dir = getVelocity();

        // Damage scales with v^2.
        float baseDamage = (float) Math.max(2.0d, energy / 400.0d);

        // Heavy armor durability damage.
        int armorDmg = (int) Math.max(1L, Math.round(energy / 72.0d));
        damageWornArmor(living, armorDmg);

        Entity owner = getOwner();
        living.damage(getDamageSources().arrow(this,
                owner instanceof LivingEntity lo ? lo : owner), baseDamage);

        // Penetration: probability based on energy vs. max HP cost.
        double cost = Math.max(1.0f, living.getMaxHealth()) * ENTITY_ENERGY_PER_HP;
        double pierceProb = energy / (energy + cost);
        boolean pierced = energy >= cost * 0.5d && random.nextDouble() < pierceProb;

        if (pierced && energy > cost) {
            piercedEntities.add(living.getUuid());
            // Lose energy proportional to entity HP.
            setEnergy(energy - cost, dir);
            // Slight deflection.
            jitterVelocity(0.04d);
            if (getWorld() instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.CRIT, getX(), getY(), getZ(),
                        12, 0.15d, 0.15d, 0.15d, 0.05d);
            }
            return;
        }

        // No penetration: stick / discard like a normal arrow.
        super.onEntityHit(result);
    }

    private void damageWornArmor(LivingEntity living, int amount) {
        if (amount <= 0) return;
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack armor = living.getEquippedStack(slot);
            if (armor.isEmpty() || !armor.isDamageable()) continue;
            armor.damage(amount, living, e -> e.sendEquipmentBreakStatus(slot));
        }
    }

    @Override
    protected void onBlockHit(BlockHitResult hit) {
        if (getWorld().isClient()) {
            super.onBlockHit(hit);
            return;
        }

        BlockPos pos = hit.getBlockPos();
        BlockState state = getWorld().getBlockState(pos);
        if (state.isAir()) {
            super.onBlockHit(hit);
            return;
        }

        double energy = energy();
        double speedMs = Math.sqrt(energy);
        float br = state.getBlock().getBlastResistance();
        double breakCost = Math.max(0.0d, br) * BLOCK_ENERGY_PER_BR;

        // Unbreakable check: bedrock-style blocks have very high blast resistance and hardness < 0
        if (state.getHardness(getWorld(), pos) < 0.0f) {
            handleUnbreakable(hit, energy, speedMs, br);
            return;
        }

        if (energy >= breakCost && breakCost > 0.0d) {
            penetrateBlock(hit, pos, state, energy, breakCost);
            return;
        }
        if (breakCost == 0.0d) {
            // Free to break (e.g. tall grass, plants)
            penetrateBlock(hit, pos, state, energy, 0.0d);
            return;
        }

        handleUnbreakable(hit, energy, speedMs, br);
    }

    private void penetrateBlock(BlockHitResult hit, BlockPos pos, BlockState state,
                                double energy, double breakCost) {
        ServerWorld sw = (ServerWorld) getWorld();
        Vec3d dir = getVelocity();

        // Break without drops.
        sw.breakBlock(pos, false, getOwner());

        // Block-fragment particles at impact.
        sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d,
                25, 0.3d, 0.3d, 0.3d, 0.1d);
        sw.playSound(null, pos, state.getSoundGroup().getBreakSound(),
                net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);

        // Reduce energy & apply accuracy loss proportional to blast resistance.
        double newEnergy = Math.max(1.0d, energy - breakCost);
        setEnergy(newEnergy, dir);
        // Inaccuracy: heavier blocks deflect the arrow more.
        double br = state.getBlock().getBlastResistance();
        double jitter = MathHelper.clamp(br * 0.05d, 0.0d, 0.4d);
        jitterVelocity(jitter);

        // Cone-shaped fragment spray if the next block in flight direction is air.
        if (dir.lengthSquared() > 1.0E-8d) {
            Vec3d nDir = dir.normalize();
            BlockPos beyond = BlockPos.ofFloored(
                    pos.getX() + 0.5d + nDir.x,
                    pos.getY() + 0.5d + nDir.y,
                    pos.getZ() + 0.5d + nDir.z);
            if (getWorld().getBlockState(beyond).isAir()) {
                spawnFragmentCone(sw, hit.getPos(), nDir, state, newEnergy);
            }
        }
    }

    private void handleUnbreakable(BlockHitResult hit, double energy, double speedMs, float br) {
        if (speedMs > SHATTER_SPEED_MS) {
            // Shatter: shrapnel kill in a small radius.
            shatter(hit, energy);
            return;
        }
        // Otherwise: normal stop.
        super.onBlockHit(hit);
    }

    private void shatter(BlockHitResult hit, double energy) {
        ServerWorld sw = (ServerWorld) getWorld();
        Vec3d at = hit.getPos();

        sw.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        sw.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 60, 0.6d, 0.6d, 0.6d, 0.6d);
        sw.spawnParticles(ParticleTypes.SMOKE, at.x, at.y, at.z, 25, 0.4d, 0.4d, 0.4d, 0.05d);
        sw.playSound(null, BlockPos.ofFloored(at), SoundEvents.ENTITY_ITEM_BREAK,
                net.minecraft.sound.SoundCategory.PLAYERS, 1.2f, 0.6f);

        double radius = 3.0d;
        float shrapnelDamage = (float) Math.max(2.0d, energy / 800.0d);
        damageEntitiesInBox(at, radius, shrapnelDamage, null);

        discard();
    }

    private void spawnFragmentCone(ServerWorld sw, Vec3d from, Vec3d dir, BlockState fragmentState, double energy) {
        // High-speed cone of block fragments forward of the impact.
        double length = 5.0d;
        int particles = 60;
        for (int i = 0; i < particles; i++) {
            double t = random.nextDouble() * length;
            double spread = 0.25d + 0.45d * (t / length); // widen with distance
            double rx = (random.nextDouble() - 0.5d) * 2.0d * spread;
            double ry = (random.nextDouble() - 0.5d) * 2.0d * spread;
            double rz = (random.nextDouble() - 0.5d) * 2.0d * spread;
            double px = from.x + dir.x * t + rx;
            double py = from.y + dir.y * t + ry;
            double pz = from.z + dir.z * t + rz;
            // Particle velocity along cone direction with slight spread.
            double vx = dir.x * 1.5d + (random.nextDouble() - 0.5d) * 0.4d;
            double vy = dir.y * 1.5d + (random.nextDouble() - 0.5d) * 0.4d;
            double vz = dir.z * 1.5d + (random.nextDouble() - 0.5d) * 0.4d;
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, fragmentState),
                    px, py, pz, 0, vx, vy, vz, 1.0d);
        }

        // Damage entities inside the cone.
        float coneDamage = (float) Math.max(1.5d, energy / 1200.0d);
        Vec3d coneCenter = from.add(dir.multiply(length * 0.5d));
        Box coneBox = new Box(
                coneCenter.x - length, coneCenter.y - length, coneCenter.z - length,
                coneCenter.x + length, coneCenter.y + length, coneCenter.z + length);
        for (Entity e : sw.getOtherEntities(this, coneBox, x ->
                x.isAlive() && x instanceof LivingEntity && x != getOwner())) {
            Vec3d toEntity = e.getPos().subtract(from);
            double along = toEntity.dotProduct(dir);
            if (along <= 0.0d || along > length) continue;
            // Distance from cone axis.
            Vec3d projected = dir.multiply(along);
            double radial = toEntity.subtract(projected).length();
            double allowedRadius = 0.25d + 0.45d * (along / length);
            if (radial > allowedRadius * 1.5d) continue;
            Entity owner = getOwner();
            e.damage(getDamageSources().arrow(this,
                    owner instanceof LivingEntity lo ? lo : owner), coneDamage);
        }
    }

    private void damageEntitiesInBox(Vec3d at, double radius, float damage, Vec3d ignoreDir) {
        ServerWorld sw = (ServerWorld) getWorld();
        Box box = new Box(at.x - radius, at.y - radius, at.z - radius,
                at.x + radius, at.y + radius, at.z + radius);
        Entity owner = getOwner();
        for (Entity e : sw.getOtherEntities(this, box, x ->
                x.isAlive() && x instanceof LivingEntity && x != owner)) {
            double dist = e.getPos().distanceTo(at);
            if (dist > radius) continue;
            float d = damage * (float) (1.0d - dist / (radius + 0.001d));
            if (d <= 0.0f) continue;
            e.damage(getDamageSources().arrow(this,
                    owner instanceof LivingEntity lo ? lo : owner), d);
        }
    }

    private void jitterVelocity(double amount) {
        if (amount <= 0.0d) return;
        Vec3d v = getVelocity();
        double speed = v.length();
        if (speed < 1.0E-6d) return;
        double jx = (random.nextDouble() - 0.5d) * 2.0d * amount;
        double jy = (random.nextDouble() - 0.5d) * 2.0d * amount;
        double jz = (random.nextDouble() - 0.5d) * 2.0d * amount;
        Vec3d nv = v.normalize().add(jx, jy, jz).normalize().multiply(speed);
        setVelocity(nv);
        velocityModified = true;
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        long[] arr = new long[piercedEntities.size() * 2];
        int i = 0;
        for (UUID u : piercedEntities) {
            arr[i++] = u.getMostSignificantBits();
            arr[i++] = u.getLeastSignificantBits();
        }
        nbt.putLongArray("PiercedEntities", arr);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        piercedEntities.clear();
        long[] arr = nbt.getLongArray("PiercedEntities");
        for (int i = 0; i + 1 < arr.length; i += 2) {
            piercedEntities.add(new UUID(arr[i], arr[i + 1]));
        }
    }
}
