package com.lightning.northstar.block.tech.rocket_thruster;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class RocketThrusterBlock extends Block implements IBE<RocketThrusterBlockEntity>, IWrenchable {

    public static final BooleanProperty TOP = BooleanProperty.create("top");
    public static final BooleanProperty BOTTOM = BooleanProperty.create("bottom");

    /**
     * Mounting face. Thrust is applied along the reverse of this direction, so
     * a thruster on the underside of a hull pushes the hull upward.
     */
    public static final DirectionProperty FACING = DirectionProperty.create("facing");
    public static final IntegerProperty TIER = IntegerProperty.create("tier", 1, 3);

    public RocketThrusterBlock(Properties properties) {
        super(properties);

        registerDefaultState(defaultBlockState()
                .setValue(TOP, false)
                .setValue(BOTTOM, false)
                .setValue(FACING, Direction.DOWN)
                .setValue(TIER, 1));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TOP, BOTTOM, FACING, TIER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hitResult) {
        if (!player.isShiftKeyDown()) return InteractionResult.PASS;
        if (!level.isClientSide) {
            level.setBlock(pos, state.setValue(TIER, state.getValue(TIER) % 3 + 1), Block.UPDATE_CLIENTS);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState oldState, boolean moved) {
        if (!(oldState.getBlock() instanceof RocketThrusterBlock))
            updateConnectivity(world, pos, true);
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!(newState.getBlock() instanceof RocketThrusterBlock))
            updateConnectivity(world, pos, false);
    }

    private void updateConnectivity(Level world, BlockPos pos, boolean added) {
        BlockState existing = world.getBlockState(pos);
        BlockState state = defaultBlockState()
                .setValue(FACING, existing.getBlock() instanceof RocketThrusterBlock
                        ? existing.getValue(FACING)
                        : Direction.DOWN)
                .setValue(TIER, existing.getBlock() instanceof RocketThrusterBlock
                        ? existing.getValue(TIER)
                        : 1)
                .setValue(TOP, updateConnectivity(world, pos.above(), added, BOTTOM))
                .setValue(BOTTOM, updateConnectivity(world, pos.below(), added, TOP));

        if (added)
            world.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
    }

    private boolean updateConnectivity(Level world, BlockPos pos, boolean added, BooleanProperty prop) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof RocketThrusterBlock) {
            world.setBlock(pos, state.setValue(prop, added).setValue(FACING, state.getValue(FACING)).setValue(TIER, state.getValue(TIER)),
                    Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
            return true;
        }
        return false;
    }

    @Override
    public Class<RocketThrusterBlockEntity> getBlockEntityClass() {
        return RocketThrusterBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends RocketThrusterBlockEntity> getBlockEntityType() {
        return NorthstarBlockEntityTypes.ROCKET_THRUSTER.get();
    }

}
