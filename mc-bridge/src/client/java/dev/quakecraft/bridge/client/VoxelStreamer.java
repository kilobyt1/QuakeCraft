package dev.quakecraft.bridge.client;

import java.lang.invoke.VarHandle;
import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Streams a 64x64x64 Minecraft block window centered around the player into
 * Local\QuakeCraftBridgeV1 (shm->voxels) so Quake 3's genuine bg_pmove.c and
 * server traces collide directly against the live Minecraft world.
 */
public final class VoxelStreamer {
	private int lastOriginX = Integer.MIN_VALUE;
	private int lastOriginY = Integer.MIN_VALUE;
	private int lastOriginZ = Integer.MIN_VALUE;
	private int ticksSinceFullScan = 0;
	private int updateCounter = 0;

	public void update(
		final SharedMemoryBridge bridge,
		final Level level,
		final double playerMcX,
		final double playerMcY,
		final double playerMcZ,
		final float anchorMcX,
		final float anchorMcY,
		final float anchorMcZ,
		final boolean forceImmediate
	) {
		final int originX = Mth.floor(playerMcX) - SharedMemoryBridge.VOXEL_HALF;
		final int originY = Mth.floor(playerMcY) - SharedMemoryBridge.VOXEL_HALF;
		final int originZ = Mth.floor(playerMcZ) - SharedMemoryBridge.VOXEL_HALF;

		final boolean moved = (originX != lastOriginX) || (originY != lastOriginY) || (originZ != lastOriginZ);
		ticksSinceFullScan++;
		if (!forceImmediate && !moved && ticksSinceFullScan < 5) {
			return;
		}

		lastOriginX = originX;
		lastOriginY = originY;
		lastOriginZ = originZ;
		ticksSinceFullScan = 0;
		updateCounter++;

		final var seg = bridge.segment;
		int seq = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ);
		if ((seq & 1) != 0) {
			seq++;
		}
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, seq + 1);
		VarHandle.fullFence();

		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_X, originX);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Y, originY);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Z, originZ);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_X, anchorMcX);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_Y, anchorMcY);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_Z, anchorMcZ);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_UPDATE_COUNT, updateCounter);

		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dy = 0; dy < SharedMemoryBridge.VOXEL_DIM; dy++) {
			final int wy = originY + dy;
			final long yBase = SharedMemoryBridge.OFF_VOXEL_CELLS
				+ ((long) dy * SharedMemoryBridge.VOXEL_DIM * SharedMemoryBridge.VOXEL_DIM);
			for (int dz = 0; dz < SharedMemoryBridge.VOXEL_DIM; dz++) {
				final int wz = originZ + dz;
				final long zBase = yBase + ((long) dz * SharedMemoryBridge.VOXEL_DIM);
				for (int dx = 0; dx < SharedMemoryBridge.VOXEL_DIM; dx++) {
					pos.set(originX + dx, wy, wz);
					final byte code = encodeBlockState(level, pos, level.getBlockState(pos));
					seg.set(SharedMemoryBridge.BYTE, zBase + dx, code);
				}
			}
		}

		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, seq + 2);
	}

	public static byte encodeBlockState(final Level level, final BlockPos pos, final BlockState state) {
		if (state.isAir()) {
			return (byte) SharedMemoryBridge.VOX_AIR;
		}

		final Block block = state.getBlock();
		int flags = 0;
		if (block instanceof IceBlock || state.is(BlockTags.ICE)) {
			flags |= SharedMemoryBridge.SURF_SLICK;
		}
		if (block instanceof SlimeBlock) {
			flags |= SharedMemoryBridge.SURF_BOUNCE;
		}
		if (block instanceof HalfTransparentBlock || block == Blocks.TNT || state.is(BlockTags.IMPERMEABLE)) {
			flags |= SharedMemoryBridge.SURF_BREAKABLE;
		}
		if (block == Blocks.IRON_BLOCK || block == Blocks.IRON_BARS || block == Blocks.ANVIL) {
			flags |= SharedMemoryBridge.SURF_METAL;
		}

		if (block instanceof LadderBlock || state.is(BlockTags.CLIMBABLE)) {
			return (byte) (SharedMemoryBridge.VOX_LADDER | flags);
		}

		final FluidState fluid = state.getFluidState();
		if (!fluid.isEmpty()) {
			if (fluid.is(FluidTags.LAVA)) {
				return (byte) (SharedMemoryBridge.VOX_LAVA | flags);
			}
			if (fluid.is(FluidTags.WATER)) {
				return (byte) (SharedMemoryBridge.VOX_WATER | flags);
			}
		}

		if (block instanceof SlabBlock && state.hasProperty(SlabBlock.TYPE)) {
			final SlabType slabType = state.getValue(SlabBlock.TYPE);
			if (slabType == SlabType.BOTTOM) {
				return (byte) (SharedMemoryBridge.VOX_SLAB_BOTTOM | flags);
			}
			if (slabType == SlabType.TOP) {
				return (byte) (SharedMemoryBridge.VOX_SLAB_TOP | flags);
			}
			return (byte) (SharedMemoryBridge.VOX_SOLID | flags);
		}

		if (block instanceof StairBlock && state.hasProperty(StairBlock.FACING)) {
			if (state.hasProperty(StairBlock.HALF) && state.getValue(StairBlock.HALF) == Half.TOP) {
				return (byte) (SharedMemoryBridge.VOX_SOLID | flags);
			}
			final Direction facing = state.getValue(StairBlock.FACING);
			final int shape = switch (facing) {
				case NORTH -> SharedMemoryBridge.VOX_STAIR_N;
				case SOUTH -> SharedMemoryBridge.VOX_STAIR_S;
				case EAST -> SharedMemoryBridge.VOX_STAIR_E;
				case WEST -> SharedMemoryBridge.VOX_STAIR_W;
				default -> SharedMemoryBridge.VOX_SOLID;
			};
			return (byte) (shape | flags);
		}

		if (block instanceof FenceBlock || block instanceof WallBlock || state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS)) {
			return (byte) (SharedMemoryBridge.VOX_FENCE | flags);
		}

		final VoxelShape collision = state.getCollisionShape(level, pos);
		if (collision.isEmpty()) {
			return (byte) SharedMemoryBridge.VOX_AIR;
		}
		final double maxY = collision.max(Direction.Axis.Y);
		if (maxY <= 0.5625) {
			return (byte) (SharedMemoryBridge.VOX_SLAB_BOTTOM | flags);
		}
		return (byte) (SharedMemoryBridge.VOX_SOLID | flags);
	}
}
