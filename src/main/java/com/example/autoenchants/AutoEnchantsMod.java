package com.example.autoenchants;

import com.example.autoenchants.entity.PeekabooShellEntity;
import com.example.autoenchants.entity.PeekabooSparkEntity;
import com.example.autoenchants.entity.SuperGolemSnowballEntity;
import com.example.autoenchants.entity.SuperSnowGolemEntity;
import com.example.autoenchants.entity.SquidMissileEntity;
import com.example.autoenchants.item.SquidMissileItem;
import com.example.autoenchants.effect.LockedOnEffect;
import com.example.autoenchants.effect.ReactionArmorCooldownEffect;
import com.example.autoenchants.effect.RetroBootsCooldownEffect;
import com.example.autoenchants.effect.SquidIronFistCooldownEffect;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentLevelEntry;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.EvokerFangsEntity;
import net.minecraft.entity.passive.SnowGolemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.CompassItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

public class AutoEnchantsMod implements ModInitializer {
    public static final String MOD_ID = "autoenchants";

    public static final RegistryKey<Enchantment> PRECISE_SHOOTER = enchantmentKey("precise_shooter");
    public static final RegistryKey<Enchantment> AUTOMATIC = enchantmentKey("automatic");
    public static final RegistryKey<Enchantment> TRIPLE_BURST = enchantmentKey("triple_burst");
    public static final RegistryKey<Enchantment> BLAST_FIREWORK = enchantmentKey("blast_firework");
    public static final RegistryKey<Enchantment> FIREWORK_SHULKER = enchantmentKey("firework_shulker");
    public static final RegistryKey<Enchantment> FIREWORK_GOLEM = enchantmentKey("firework_golem");
    public static final RegistryKey<Enchantment> FIREWORK_CREEPER = enchantmentKey("firework_creeper");
    public static final RegistryKey<Enchantment> FIREWORK_VEX = enchantmentKey("firework_vex");
    public static final RegistryKey<Enchantment> CRITICAL_FANGS = enchantmentKey("critical_fangs");
    public static final RegistryKey<Enchantment> THERMAL_HELMET = enchantmentKey("thermal_helmet");
    public static final RegistryKey<Enchantment> REQUIEM = enchantmentKey("requiem");
    public static final RegistryKey<Enchantment> SKY_BOMBARD = enchantmentKey("sky_bombard");
    public static final RegistryKey<Enchantment> GUIDANCE = enchantmentKey("guidance");
    public static final RegistryKey<Enchantment> PRECISE_GUIDANCE = enchantmentKey("precise_guidance");
    public static final RegistryKey<Enchantment> REACTION_ARMOR = enchantmentKey("reaction_armor");
    public static final RegistryKey<Enchantment> SQUID_IRON_FIST = enchantmentKey("squid_iron_fist");
    public static final RegistryKey<Enchantment> STRANGE_WAND = enchantmentKey("strange_wand");
    public static final RegistryKey<Enchantment> RETRO_BOOTS = enchantmentKey("retro_boots");
    public static final RegistryKey<Enchantment> EXPLOSIVE_TRIDENT = enchantmentKey("explosive_trident");
    private static final RegistryKey<EntityType<?>> PEEKABOO_SHELL_KEY = entityTypeKey("peekaboo_shell");
    private static final RegistryKey<EntityType<?>> SQUID_MISSILE_KEY = entityTypeKey("squid_missile");
    private static final RegistryKey<EntityType<?>> PEEKABOO_SPARK_KEY = entityTypeKey("peekaboo_spark");
    private static final RegistryKey<EntityType<?>> SUPER_GOLEM_SNOWBALL_KEY = entityTypeKey("super_golem_snowball");
    private static final RegistryKey<EntityType<?>> SUPER_SNOW_GOLEM_KEY = entityTypeKey("super_snow_golem");
    public static Item TARGET_POINTER;
    public static Item PEEKABOO_SHELL_SPAWN_EGG;
    public static Item SQUID_MISSILE_ITEM;
    public static EntityType<PeekabooShellEntity> PEEKABOO_SHELL;
    public static EntityType<PeekabooSparkEntity> PEEKABOO_SPARK;
    public static EntityType<SquidMissileEntity> SQUID_MISSILE;
    public static EntityType<SuperSnowGolemEntity> SUPER_SNOW_GOLEM;
    public static EntityType<SuperGolemSnowballEntity> SUPER_GOLEM_SNOWBALL;
    public static StatusEffect LOCKED_ON;
    public static StatusEffect REACTION_ARMOR_COOLDOWN;
    public static StatusEffect SQUID_IRON_FIST_COOLDOWN;
    public static StatusEffect RETRO_BOOTS_COOLDOWN;
    public static Item SUPER_SNOW_GOLEM_SPAWN_EGG;

    @Override
    public void onInitialize() {
        PEEKABOO_SHELL = Registry.register(
                Registries.ENTITY_TYPE,
                id("peekaboo_shell"),
                FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, PeekabooShellEntity::new)
                        .dimensions(EntityDimensions.fixed(1.0f, 1.0f))
                        .trackRangeBlocks(80)
                        .build(PEEKABOO_SHELL_KEY)
        );
        FabricDefaultAttributeRegistry.register(PEEKABOO_SHELL, PeekabooShellEntity.createShulkerAttributes());

        SQUID_MISSILE = Registry.register(
                Registries.ENTITY_TYPE,
                id("squid_missile"),
                FabricEntityTypeBuilder.<SquidMissileEntity>create(SpawnGroup.MISC, SquidMissileEntity::new)
                        .dimensions(EntityDimensions.fixed(0.8f, 0.8f))
                        .trackRangeBlocks(80)
                        .trackedUpdateRate(2)
                        .build(SQUID_MISSILE_KEY)
        );
        FabricDefaultAttributeRegistry.register(SQUID_MISSILE, SquidMissileEntity.createMissileAttributes());

        PEEKABOO_SPARK = Registry.register(
                Registries.ENTITY_TYPE,
                id("peekaboo_spark"),
                FabricEntityTypeBuilder.<PeekabooSparkEntity>create(SpawnGroup.MISC, PeekabooSparkEntity::new)
                        .dimensions(EntityDimensions.fixed(0.3125f, 0.3125f))
                        .trackRangeBlocks(80)
                        .trackedUpdateRate(2)
                        .build(PEEKABOO_SPARK_KEY)
        );

        SUPER_GOLEM_SNOWBALL = Registry.register(
                Registries.ENTITY_TYPE,
                id("super_golem_snowball"),
                FabricEntityTypeBuilder.<SuperGolemSnowballEntity>create(SpawnGroup.MISC, SuperGolemSnowballEntity::new)
                        .dimensions(EntityDimensions.fixed(0.25f, 0.25f))
                        .trackRangeBlocks(64)
                        .trackedUpdateRate(10)
                        .build(SUPER_GOLEM_SNOWBALL_KEY)
        );

        SUPER_SNOW_GOLEM = Registry.register(
                Registries.ENTITY_TYPE,
                id("super_snow_golem"),
                FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, SuperSnowGolemEntity::new)
                        .dimensions(EntityDimensions.fixed(0.7f, 1.9f))
                        .trackRangeBlocks(80)
                        .build(SUPER_SNOW_GOLEM_KEY)
        );
        FabricDefaultAttributeRegistry.register(SUPER_SNOW_GOLEM, SnowGolemEntity.createSnowGolemAttributes());

        TARGET_POINTER = Registry.register(
                Registries.ITEM,
                id("target_pointer"),
                new CompassItem(new Item.Settings().maxCount(1))
        );

        PEEKABOO_SHELL_SPAWN_EGG = Registry.register(
                Registries.ITEM,
                id("peekaboo_shell_spawn_egg"),
                new SpawnEggItem(new Item.Settings().spawnEgg(PEEKABOO_SHELL))
        );

        SQUID_MISSILE_ITEM = Registry.register(
                Registries.ITEM,
                id("squid_missile"),
                new SquidMissileItem(new Item.Settings().maxCount(16))
        );

        SUPER_SNOW_GOLEM_SPAWN_EGG = Registry.register(
                Registries.ITEM,
                id("super_snow_golem_spawn_egg"),
                new SpawnEggItem(new Item.Settings().spawnEgg(SUPER_SNOW_GOLEM))
        );

        Registry.register(Registries.ITEM_GROUP, id("main"),
                FabricItemGroup.builder()
                        .icon(() -> new ItemStack(TARGET_POINTER))
                        .displayName(Text.translatable("itemGroup.autoenchants.main"))
                        .entries((context, entries) -> {
                            entries.add(TARGET_POINTER);
                            entries.add(SQUID_MISSILE_ITEM);
                            entries.add(PEEKABOO_SHELL_SPAWN_EGG);
                            entries.add(SUPER_SNOW_GOLEM_SPAWN_EGG);
                            addEnchantedBooks(context.lookup(), entries);
                        })
                        .build()
        );

        LOCKED_ON = Registry.register(
                Registries.STATUS_EFFECT,
                id("locked_on"),
                new LockedOnEffect()
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

        RETRO_BOOTS_COOLDOWN = Registry.register(
                Registries.STATUS_EFFECT,
                id("retro_boots_cooldown"),
                new RetroBootsCooldownEffect()
        );

        ServerTickEvents.END_SERVER_TICK.register(AutoFireHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(ThermalVisionHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(RequiemHornHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(HostilePerceptionHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(ReactionArmorHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(SquidIronFistHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(LockedOnHandler::tick);
        ServerTickEvents.END_SERVER_TICK.register(RetroBootsHandler::tick);
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (world.isClient() || !(stack.getItem() instanceof CrossbowItem)) {
                return ActionResult.PASS;
            }
            if (AutoFireHandler.startTripleBurst(player, hand, stack)) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
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
        return Identifier.of(MOD_ID, path);
    }

    public static int getEnchantmentLevel(RegistryKey<Enchantment> enchantmentKey, ItemStack stack) {
        ItemEnchantmentsComponent enchantments = EnchantmentHelper.getEnchantments(stack);
        for (var entry : enchantments.getEnchantmentEntries()) {
            if (entry.getKey().matchesKey(enchantmentKey)) {
                return entry.getIntValue();
            }
        }
        return 0;
    }

    public static int getEquipmentEnchantmentLevel(RegistryKey<Enchantment> enchantmentKey, LivingEntity entity) {
        int level = 0;
        for (EquipmentSlot slot : EquipmentSlot.VALUES) {
            if (!slot.isArmorSlot()) {
                continue;
            }
            level = Math.max(level, getEnchantmentLevel(enchantmentKey, entity.getEquippedStack(slot)));
        }
        level = Math.max(level, getEnchantmentLevel(enchantmentKey, entity.getMainHandStack()));
        level = Math.max(level, getEnchantmentLevel(enchantmentKey, entity.getOffHandStack()));
        return level;
    }

    private static RegistryEntry.Reference<Enchantment> getEnchantmentEntry(RegistryWrapper.WrapperLookup lookup, RegistryKey<Enchantment> enchantmentKey) {
        return lookup.getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(enchantmentKey);
    }

    private static void addEnchantedBooks(RegistryWrapper.WrapperLookup lookup, ItemGroup.Entries entries) {
        RegistryKey<Enchantment>[] enchantments = new RegistryKey[]{
                PRECISE_SHOOTER, AUTOMATIC, TRIPLE_BURST,
                BLAST_FIREWORK, FIREWORK_SHULKER, FIREWORK_GOLEM, FIREWORK_CREEPER, FIREWORK_VEX,
                PRECISE_GUIDANCE,
                CRITICAL_FANGS, SKY_BOMBARD, EXPLOSIVE_TRIDENT,
                THERMAL_HELMET, SQUID_IRON_FIST, REACTION_ARMOR,
                GUIDANCE, REQUIEM, STRANGE_WAND, RETRO_BOOTS
        };
        for (RegistryKey<Enchantment> enchantmentKey : enchantments) {
            RegistryEntry.Reference<Enchantment> entry = getEnchantmentEntry(lookup, enchantmentKey);
            for (int level = 1; level <= entry.value().getMaxLevel(); level++) {
                entries.add(EnchantmentHelper.getEnchantedBookWith(new EnchantmentLevelEntry(entry, level)));
            }
        }
    }

    private static void autoenchants$trySpawnCriticalFangs(PlayerEntity player, LivingEntity target) {
        ItemStack weapon = player.getMainHandStack();
        int level = getEnchantmentLevel(CRITICAL_FANGS, weapon);
        if (level <= 0) {
            return;
        }
        if (!autoenchants$isCriticalAttack(player) || !autoenchants$isNearGround(target)) {
            return;
        }

        World world = player.getEntityWorld();
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
        World world = target.getEntityWorld();
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

    private static RegistryKey<Enchantment> enchantmentKey(String path) {
        return RegistryKey.of(RegistryKeys.ENCHANTMENT, id(path));
    }

    private static RegistryKey<EntityType<?>> entityTypeKey(String path) {
        return RegistryKey.of(RegistryKeys.ENTITY_TYPE, id(path));
    }
}
