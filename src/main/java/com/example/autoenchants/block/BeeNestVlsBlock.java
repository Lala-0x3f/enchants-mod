package com.example.autoenchants.block;

import com.example.autoenchants.block.entity.BeeNestVlsBlockEntity;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ItemScatterer;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class BeeNestVlsBlock extends BlockWithEntity implements BlockEntityProvider {
    public static final MapCodec<BeeNestVlsBlock> CODEC = createCodec(BeeNestVlsBlock::new);
    public static final BooleanProperty LOADED = BooleanProperty.of("loaded");

    public BeeNestVlsBlock(Settings settings) {
        super(settings);
        this.setDefaultState(this.stateManager.getDefaultState().with(LOADED, false));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LOADED);
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(LOADED, false);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new BeeNestVlsBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
        return world.isClient ? null : (tickWorld, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof BeeNestVlsBlockEntity vls) {
                BeeNestVlsBlockEntity.tick(tickWorld, pos, tickState, vls);
            }
        };
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        ItemStack held = player.getStackInHand(hand);
        if (!(world.getBlockEntity(pos) instanceof BeeNestVlsBlockEntity vls) || !vls.isAcceptedMissile(held)) {
            return ActionResult.PASS;
        }
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        int countBefore = vls.getMissileCount();
        if (!vls.addMissile(held, player.getAbilities().creativeMode)) {
            return ActionResult.CONSUME;
        }
        int countAfter = vls.getMissileCount();

        // 音高随导弹数量递增：从0.8到1.6，便于玩家判断装填量
        float pitch = 0.8f + (countAfter / 12.0f) * 0.8f;
        world.playSound(null, pos, SoundEvents.BLOCK_BEEHIVE_ENTER, SoundCategory.BLOCKS, 0.8f, pitch);

        ((ServerWorld) world).spawnParticles(ParticleTypes.LANDING_HONEY,
                pos.getX() + 0.5d, pos.getY() + 0.8d, pos.getZ() + 0.5d,
                10, 0.25d, 0.2d, 0.25d, 0.01d);
        // 额外的蜂蜜滴落粒子
        ((ServerWorld) world).spawnParticles(ParticleTypes.DRIPPING_HONEY,
                pos.getX() + 0.5d, pos.getY() + 0.9d, pos.getZ() + 0.5d,
                3, 0.3d, 0.1d, 0.3d, 0.0d);
        return ActionResult.CONSUME;
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock()) && world.getBlockEntity(pos) instanceof BeeNestVlsBlockEntity vls) {
            ItemScatterer.spawn(world, pos, vls.getItems());
            world.updateComparators(pos, this);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
