package com.example.autoenchants;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.CaveSpiderEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.GuardianEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.RavagerEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.VexEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraft.world.WorldEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StrangeWandHandler {
    private static final int COOLDOWN_TICKS = 60;
    private static final Map<UUID, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();

    private static final List<EntityType<? extends MobEntity>> FLYING_POOL = List.of(
            EntityType.PHANTOM,
            EntityType.VEX
    );

    private static final List<EntityType<? extends MobEntity>> AQUATIC_POOL = List.of(
            EntityType.GUARDIAN,
            EntityType.ELDER_GUARDIAN
    );

    private static final List<EntityType<? extends MobEntity>> QUADRUPED_POOL = List.of(
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.CREEPER,
            EntityType.RAVAGER
    );

    private static final List<EntityType<? extends MobEntity>> BIPED_POOL = List.of(
            EntityType.ZOMBIE,
            EntityType.HUSK,
            EntityType.DROWNED,
            EntityType.SKELETON,
            EntityType.STRAY,
            EntityType.WITCH,
            EntityType.PILLAGER,
            EntityType.VINDICATOR,
            EntityType.EVOKER
    );

    private StrangeWandHandler() {
    }

    public static ActionResult onUseEntity(ServerPlayerEntity player, World world, Hand hand, Entity entity) {
        if (world.isClient()) {
            return ActionResult.PASS;
        }
        if (world.getRegistryKey() != World.OVERWORLD) {
            return ActionResult.PASS;
        }

        ItemStack stack = player.getStackInHand(hand);
        if (!stack.isOf(Items.STICK) || EnchantmentHelper.getLevel(AutoEnchantsMod.STRANGE_WAND, stack) <= 0) {
            return ActionResult.PASS;
        }

        long now = world.getTime();
        long until = COOLDOWN_UNTIL.getOrDefault(player.getUuid(), 0L);
        if (now < until) {
            return ActionResult.FAIL;
        }

        if (entity instanceof VillagerEntity villager) {
            applyVillagerEffect((ServerWorld) world, villager);
            setCooldown(player, now);
            return ActionResult.SUCCESS;
        }

        if (!(entity instanceof LivingEntity living) || !isHostile(living)) {
            return ActionResult.PASS;
        }

        spawnEnchantParticles((ServerWorld) world, living);
        if (world.random.nextFloat() < 0.5f) {
            transformHostile((ServerWorld) world, living);
        }
        setCooldown(player, now);
        return ActionResult.SUCCESS;
    }

    private static void applyVillagerEffect(ServerWorld world, VillagerEntity villager) {
        villager.heal(4.0f);
        villager.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                net.minecraft.entity.effect.StatusEffects.REGENERATION, 120, 1, false, true, true
        ));
        spawnEnchantParticles(world, villager);
        if (!villager.isBaby() && world.random.nextFloat() < 0.2f) {
            villager.setBaby(true);
            world.syncWorldEvent(WorldEvents.ZOMBIE_INFECTS_VILLAGER, villager.getBlockPos(), 0);
        }
    }

    private static void transformHostile(ServerWorld world, LivingEntity original) {
        EntityType<? extends MobEntity> newType = pickTransformedType(original, world);
        if (newType == null || newType == original.getType()) {
            return;
        }
        MobEntity replacement = newType.create(world);
        if (replacement == null) {
            return;
        }
        replacement.refreshPositionAndAngles(original.getX(), original.getY(), original.getZ(), original.getYaw(), original.getPitch());
        replacement.setHealth(Math.min(replacement.getMaxHealth(), original.getHealth()));
        replacement.initialize(world, world.getLocalDifficulty(replacement.getBlockPos()), SpawnReason.CONVERSION, null, null);
        world.spawnEntity(replacement);
        spawnEnchantParticles(world, replacement);
        original.discard();
    }

    private static EntityType<? extends MobEntity> pickTransformedType(LivingEntity hostile, ServerWorld world) {
        BodyClass bodyClass = classify(hostile);
        List<EntityType<? extends MobEntity>> pool = switch (bodyClass) {
            case FLYING -> FLYING_POOL;
            case AQUATIC -> AQUATIC_POOL;
            case QUADRUPED -> QUADRUPED_POOL;
            case BIPED -> BIPED_POOL;
        };
        if (pool.isEmpty()) {
            return null;
        }
        List<EntityType<? extends MobEntity>> candidates = new ArrayList<>();
        for (EntityType<? extends MobEntity> type : pool) {
            if (type != hostile.getType()) {
                candidates.add(type);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(world.random.nextInt(candidates.size()));
    }

    private static BodyClass classify(LivingEntity hostile) {
        if (hostile instanceof PhantomEntity || hostile instanceof VexEntity) {
            return BodyClass.FLYING;
        }
        if (hostile instanceof GuardianEntity) {
            return BodyClass.AQUATIC;
        }
        if (hostile instanceof SpiderEntity || hostile instanceof CaveSpiderEntity || hostile instanceof CreeperEntity || hostile instanceof RavagerEntity) {
            return BodyClass.QUADRUPED;
        }
        return BodyClass.BIPED;
    }

    private static boolean isHostile(LivingEntity entity) {
        return entity instanceof HostileEntity || entity instanceof Monster;
    }

    private static void spawnEnchantParticles(ServerWorld world, LivingEntity entity) {
        world.spawnParticles(
                ParticleTypes.ENCHANT,
                entity.getX(),
                entity.getBodyY(0.5d),
                entity.getZ(),
                24,
                0.45d,
                0.35d,
                0.45d,
                0.01d
        );
    }

    private static void setCooldown(ServerPlayerEntity player, long nowTick) {
        COOLDOWN_UNTIL.put(player.getUuid(), nowTick + COOLDOWN_TICKS);
    }

    private enum BodyClass {
        FLYING,
        AQUATIC,
        QUADRUPED,
        BIPED
    }
}
