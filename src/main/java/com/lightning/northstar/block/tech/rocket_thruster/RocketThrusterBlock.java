package com.lightning.northstar.block.tech.rocket_thruster;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class RocketThrusterBlock extends Block implements IWrenchable {

    public static final BooleanProperty TOP = BooleanProperty.create("top");
    public static final BooleanProperty BOTTOM = BooleanProperty.create("bottom");

    /**
     * Mounting face. Thrust is applied along the reverse of this direction, so
     * a thruster on the underside of a hull pushes the hull upward.
     */
    public static final DirectionProperty FACING = DirectionProperty.create("facing");

    public RocketThrusterBlock(Properties properties) {
        super(properties);

        registerDefaultState(defaultBlockState()
                .setValue(TOP, false)
                .setValue(BOTTOM, false)
                .setValue(FACING, Direction.DOWN));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(TOP, BOTTOM, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
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
                .setValue(TOP, updateConnectivity(world, pos.above(), added, BOTTOM))
                .setValue(BOTTOM, updateConnectivity(world, pos.below(), added, TOP));

        if (added)
            world.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
    }

    private boolean updateConnectivity(Level world, BlockPos pos, boolean added, BooleanProperty prop) {
        BlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof RocketThrusterBlock) {
            world.setBlock(pos, state.setValue(prop, added).setValue(FACING, state.getValue(FACING)),
                    Block.UPDATE_CLIENTS | Block.UPDATE_INVISIBLE | Block.UPDATE_KNOWN_SHAPE);
            return true;
        }
        return false;
    }

}
