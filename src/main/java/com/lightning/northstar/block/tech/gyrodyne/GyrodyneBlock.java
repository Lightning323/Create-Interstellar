package com.lightning.northstar.block.tech.gyrodyne;

import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;
import com.lightning.northstar.content.NorthstarBlockEntityTypes;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

import javax.annotation.ParametersAreNonnullByDefault;

/**
 * A reaction wheel that steers a vessel by torquing it, rather than by pushing it
 * off-axis like a thruster does.
 *
 * <p>{@link #FACING} is the mounting face. It fixes the gyrodyne's local frame: the
 * wheel's axis is that face's outward normal, and the direction modes steer that
 * axis. A player picks a facing to say which way they want the vessel pointed.
 *
 * <p>{@link #POWERED} is a disengage, not an enable — see
 * {@link GyrodyneBlockEntity#isActive()}. Redstone the block to kill its torque
 * mid-flight, which is the failure mode a reaction wheel needs to be interruptible
 * for.
 */
@ParametersAreNonnullByDefault
public class GyrodyneBlock extends DirectionalBlock implements IBE<GyrodyneBlockEntity>, IWrenchable {

    public static final MapCodec<GyrodyneBlock> CODEC = simpleCodec(GyrodyneBlock::new);

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public GyrodyneBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.UP)
                .setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends DirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getClickedFace())
                .setValue(POWERED, context.getLevel().hasNeighborSignal(context.getClickedPos()));
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                   BlockPos fromPos, boolean isMoving) {
        if (level.isClientSide) {
            return;
        }
        boolean signalled = level.hasNeighborSignal(pos);
        if (state.getValue(POWERED) != signalled) {
            level.setBlock(pos, state.setValue(POWERED, signalled), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public Class<GyrodyneBlockEntity> getBlockEntityClass() {
        return GyrodyneBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends GyrodyneBlockEntity> getBlockEntityType() {
        return NorthstarBlockEntityTypes.GYRODYNE.get();
    }
}