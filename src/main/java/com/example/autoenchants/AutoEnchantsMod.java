package com.example.autoenchants;

import com.example.autoenchants.enchant.AutomaticEnchantment;
import com.example.autoenchants.enchant.BlastFireworkEnchantment;
import com.example.autoenchants.enchant.CriticalFangsEnchantment;
import com.example.autoenchants.enchant.FireworkGolemEnchantment;
import com.example.autoenchants.enchant.FireworkShulkerEnchantment;
import com.example.autoenchants.enchant.PreciseShooterEnchantment;
import com.example.autoenchants.enchant.ReactionArmorEnchantment;
import com.example.autoenchants.enchant.RequiemEnchantment;
import com.example.autoenchants.enchant.SkyBombardEnchantment;
import com.example.autoenchants.enchant.StrangeWandEnchantment;
import com.example.autoenchants.enchant.SquidIronFistEnchantment;
import com.example.autoenchants.enchant.ThermalHelmetEnchantment;
import com.example.autoenchants.enchant.TripleBurstEnchantment;
import com.example.autoenchants.effect.ReactionArmorCooldownEffect;
import com.example.autoenchants.effect.SquidIronFistCooldownEffect;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class AutoEnchantsMod implements ModInitializer {
    public static final String MOD_ID = "autoenchants";

    public static Enchantment PRECISE_SHOOTER;
    public static Enchantment AUTOMATIC;
    public static Enchantment TRIPLE_BURST;
    public static Enchantment BLAST_FIREWORK;
    public static Enchantment FIREWORK_SHULKER;
    public static Enchantment FIREWORK_GOLEM;
    public static Enchantment CRITICAL_FANGS;
    public static Enchantment THERMAL_HELMET;
    public static Enchantment REQUIEM;
    public static Enchantment SKY_BOMBARD;
    public static Enchantment REACTION_ARMOR;
    public static Enchantment SQUID_IRON_FIST;
    public static Enchantment STRANGE_WAND;
    public static StatusEffect REACTION_ARMOR_COOLDOWN;
    public static StatusEffect SQUID_IRON_FIST_COOLDOWN;

    @Override
    public void onInitialize() {
        PRECISE_SHOOTER = Registry.register(
                Registries.ENCHANTMENT,
                id("precise_shooter"),
                new PreciseShooterEnchantment()
        );

        AUTOMATIC = Registry.register(
                Registries.ENCHANTMENT,
                id("automatic"),
                new AutomaticEnchantment()
        );

        TRIPLE_BURST = Registry.register(
                Registries.ENCHANTMENT,
                id("triple_burst"),
                new TripleBurstEnchantment()
        );

        BLAST_FIREWORK = Registry.register(
                Registries.ENCHANTMENT,
                id("blast_firework"),
                new BlastFireworkEnchantment()
        );

        FIREWORK_SHULKER = Registry.register(
                Registries.ENCHANTMENT,
                id("firework_shulker"),
                new FireworkShulkerEnchantment()
        );

        FIREWORK_GOLEM = Registry.register(
                Registries.ENCHANTMENT,
                id("firework_golem"),
                new FireworkGolemEnchantment()
        );

        CRITICAL_FANGS = Registry.register(
                Registries.ENCHANTMENT,
                id("critical_fangs"),
                new CriticalFangsEnchantment()
        );

        THERMAL_HELMET = Registry.register(
                Registries.ENCHANTMENT,
                id("thermal_helmet"),
                new ThermalHelmetEnchantment()
        );

        REQUIEM = Registry.register(
                Registries.ENCHANTMENT,
                id("requiem"),
                new RequiemEnchantment()
        );

        SKY_BOMBARD = Registry.register(
                Registries.ENCHANTMENT,
                id("sky_bombard"),
                new SkyBombardEnchantment()
        );

        REACTION_ARMOR = Registry.register(
                Registries.ENCHANTMENT,
                id("reaction_armor"),
                new ReactionArmorEnchantment()
        );

        SQUID_IRON_FIST = Registry.register(
                Registries.ENCHANTMENT,
                id("squid_iron_fist"),
                new SquidIronFistEnchantment()
        );

        STRANGE_WAND = Registry.register(
                Registries.ENCHANTMENT,
                id("strange_wand"),
                new StrangeWandEnchantment()
        );

        REACTION_ARMOR_COOLDOWN = Registry.register(
                Registries.STATUS_EFFECT,
                id("reaction_armor_cooldown"),
                new ReactionArmorCooldownEffect()
        );

        SQUID_IRON_FIST_COOLDOWN = Registry.register(
                Registries.STATUS_EFFECT,
                id("squid_iron_fist_cooldown"),
                new SquidIronFistCooldownEffect()
        );

        ServerTickEvents.END_SERVER_TICK.register(AutoFireHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(ThermalVisionHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(RequiemHornHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(HostilePerceptionHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(ReactionArmorHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(SquidIronFistHandler::tick);
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient || !(stack.getItem() instanceof CrossbowItem)) {
                return TypedActionResult.pass(stack);
            }
            if (AutoFireHandler.startTripleBurst(player, hand, stack)) {
                return TypedActionResult.success(stack);
            }
            return TypedActionResult.pass(stack);
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient() || !(entity instanceof LivingEntity target)) {
                return ActionResult.PASS;
            }
            autoenchants$trySpawnCriticalFangs(player, target);
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer)) {
                return ActionResult.PASS;
            }
            return StrangeWandHandler.onUseEntity(serverPlayer, world, hand, entity);
        });
    }

    public static float getPreciseShooterMultiplier(int level) {
        if (level <= 0) {
            return 1.0f;
        }

        return 1.0f + (0.75f * level);
    }

    public static float getPreciseShooterDivergence(float baseDivergence, int level) {
        if (level <= 0) {
            return baseDivergence;
        }
        float factor = Math.max(0.05f, 1.0f - (0.2f * level));
        return baseDivergence * factor;
    }

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    private static void autoenchants$trySpawnCriticalFangs(PlayerEntity player, LivingEntity target) {
        ItemStack weapon = player.getMainHandStack();
        int level = EnchantmentHelper.getLevel(CRITICAL_FANGS, weapon);
        if (level <= 0) {
            return;
        }
        if (!autoenchants$isCriticalAttack(player) || !autoenchants$isNearGround(target)) {
            return;
        }

        World world = player.getWorld();
        Vec3d facing = player.getRotationVec(1.0f);
        Vec3d horizontal = new Vec3d(facing.x, 0.0d, facing.z);
        if (horizontal.lengthSquared() < 1.0E-4d) {
            return;
        }
        horizontal = horizontal.normalize();

        int count = 4 + level;
        double startX = player.getX() + horizontal.x * 1.2d;
        double startZ = player.getZ() + horizontal.z * 1.2d;
        float yaw = (float) MathHelper.atan2(horizontal.z, horizontal.x);
        double maxY = target.getY() + 1.0d;
        double minY = target.getY() - 1.0d;

        for (int i = 0; i < count; i++) {
            double x = startX + horizontal.x * (i * 1.1d);
            double z = startZ + horizontal.z * (i * 1.1d);
            double y = autoenchants$findGroundY(world, x, maxY, z, minY);
            if (Double.isNaN(y)) {
                continue;
            }
            // Stagger warmup so fangs appear from near to far in rapid succession.
            int warmupTicks = i;
            world.spawnEntity(new EvokerFangsEntity(world, x, y, z, yaw, warmupTicks, player));
            if (world instanceof ServerWorld serverWorld) {
                serverWorld.spawnParticles(ParticleTypes.SOUL, x, y + 0.2d, z, 12, 0.22d, 0.08d, 0.22d, 0.01d);
            }
        }
    }

    private static boolean autoenchants$isCriticalAttack(PlayerEntity player) {
        return player.fallDistance > 0.0f
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasVehicle()
                && player.getAttackCooldownProgress(0.5f) > 0.9f;
    }

    private static boolean autoenchants$isNearGround(LivingEntity target) {
        if (target.isOnGround()) {
            return true;
        }
        World world = target.getWorld();
        BlockPos basePos = target.getBlockPos();
        for (int i = 1; i <= 2; i++) {
            BlockPos checkPos = basePos.down(i);
            if (world.getBlockState(checkPos).isSideSolidFullSquare(world, checkPos, Direction.UP)) {
                double groundTopY = checkPos.getY() + 1.0d;
                if (target.getY() - groundTopY <= 1.0d) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double autoenchants$findGroundY(World world, double x, double maxY, double z, double minY) {
        BlockPos.Mutable pos = new BlockPos.Mutable(MathHelper.floor(x), MathHelper.floor(maxY), MathHelper.floor(z));
        while (pos.getY() >= MathHelper.floor(minY)) {
            BlockPos below = pos.down();
            if (world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) {
                return below.getY() + 1.0d;
            }
            pos.move(Direction.DOWN);
        }
        return Double.NaN;
    }
}
