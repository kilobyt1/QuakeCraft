package dev.quakecraft.bridge.client;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SlimeBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Streams a 64x64x64 Minecraft block window centered around the player into
 * Local\QuakeCraftBridgeV1 (shm->voxels) so Quake 3's genuine bg_pmove.c and
 * server traces collide directly against the live Minecraft world.
 *
 * Uses O(1) BlockState identity memoization, direct LevelChunkSection traversal,
 * 8-block origin snapping, and a local staging array copied atomically into
 * shared memory in ~20 microseconds so Quake 3 PMove never reads torn voxels.
 */
public final class VoxelStreamer {
	private static final java.util.concurrent.ConcurrentHashMap<BlockState, Byte> STATE_CACHE = new java.util.concurrent.ConcurrentHashMap<>(2048);

	private final byte[] syncStagingCells = new byte[SharedMemoryBridge.VOXEL_TOTAL];
	private final byte[] asyncStagingCells = new byte[SharedMemoryBridge.VOXEL_TOTAL];
	private final java.util.concurrent.ExecutorService scanExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
		final Thread t = new Thread(r, "QuakeCraft-VoxelStreamer");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private final java.util.concurrent.atomic.AtomicBoolean scanInProgress = new java.util.concurrent.atomic.AtomicBoolean(false);
	private volatile int lastOriginX = Integer.MIN_VALUE;
	private volatile int lastOriginY = Integer.MIN_VALUE;
	private volatile int lastOriginZ = Integer.MIN_VALUE;
	private volatile int pendingOriginX = Integer.MIN_VALUE;
	private volatile int pendingOriginY = Integer.MIN_VALUE;
	private volatile int pendingOriginZ = Integer.MIN_VALUE;
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
		// Snap grid origin to 8-block boundaries so the 64x64x64 window (+-32 blocks)
		// provides +-24 blocks of margin while cutting grid shifts by half to ensure
		// maximum stability during fast movement and bunny-hopping.
		final int originX = (Mth.floor(playerMcX) & ~7) - SharedMemoryBridge.VOXEL_HALF;
		final int originY = (Mth.floor(playerMcY) & ~7) - SharedMemoryBridge.VOXEL_HALF;
		final int originZ = (Mth.floor(playerMcZ) & ~7) - SharedMemoryBridge.VOXEL_HALF;

		final boolean moved = (originX != lastOriginX) || (originY != lastOriginY) || (originZ != lastOriginZ);
		ticksSinceFullScan++;

		if (forceImmediate) {
			pendingOriginX = originX;
			pendingOriginY = originY;
			pendingOriginZ = originZ;
			lastOriginX = originX;
			lastOriginY = originY;
			lastOriginZ = originZ;
			ticksSinceFullScan = 0;
			scanAndPublish(bridge, level, originX, originY, originZ, anchorMcX, anchorMcY, anchorMcZ, syncStagingCells);
			return;
		}

		if (!moved && ticksSinceFullScan < 100) {
			return;
		}

		pendingOriginX = originX;
		pendingOriginY = originY;
		pendingOriginZ = originZ;
		ticksSinceFullScan = 0;

		if (scanInProgress.compareAndSet(false, true)) {
			scanExecutor.submit(() -> {
				try {
					while (true) {
						final int curX = pendingOriginX;
						final int curY = pendingOriginY;
						final int curZ = pendingOriginZ;
						scanAndPublish(bridge, level, curX, curY, curZ, anchorMcX, anchorMcY, anchorMcZ, asyncStagingCells);
						lastOriginX = curX;
						lastOriginY = curY;
						lastOriginZ = curZ;
						if (pendingOriginX == curX && pendingOriginY == curY && pendingOriginZ == curZ) {
							break;
						}
					}
				} catch (Throwable ignored) {
				} finally {
					scanInProgress.set(false);
				}
			});
		}
	}

	private void scanAndPublish(
		final SharedMemoryBridge bridge,
		final Level level,
		final int originX,
		final int originY,
		final int originZ,
		final float anchorMcX,
		final float anchorMcY,
		final float anchorMcZ,
		final byte[] stagingCells
	) {
		Arrays.fill(stagingCells, (byte) SharedMemoryBridge.VOX_AIR);

		final int minLevelY = level.getMinY();
		final int maxLevelY = level.getMaxY();
		final int minChunkX = originX >> 4;
		final int maxChunkX = (originX + SharedMemoryBridge.VOXEL_DIM - 1) >> 4;
		final int minChunkZ = originZ >> 4;
		final int maxChunkZ = (originZ + SharedMemoryBridge.VOXEL_DIM - 1) >> 4;

		for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			final int chunkBaseZ = cz << 4;
			final int dzStart = Math.max(0, chunkBaseZ - originZ);
			final int dzEnd = Math.min(SharedMemoryBridge.VOXEL_DIM, chunkBaseZ + 16 - originZ);
			if (dzStart >= dzEnd) {
				continue;
			}

			for (int cx = minChunkX; cx <= maxChunkX; cx++) {
				if (!level.hasChunk(cx, cz)) {
					continue;
				}
				final int chunkBaseX = cx << 4;
				final int dxStart = Math.max(0, chunkBaseX - originX);
				final int dxEnd = Math.min(SharedMemoryBridge.VOXEL_DIM, chunkBaseX + 16 - originX);
				if (dxStart >= dxEnd) {
					continue;
				}

				final LevelChunk chunk = level.getChunk(cx, cz);
				if (chunk == null) {
					continue;
				}
				final LevelChunkSection[] sections = chunk.getSections();
				if (sections == null) {
					continue;
				}

				int dy = 0;
				while (dy < SharedMemoryBridge.VOXEL_DIM) {
					final int wy = originY + dy;
					if (wy < minLevelY || wy >= maxLevelY) {
						dy++;
						continue;
					}
					final int secIdx = level.getSectionIndex(wy);
					final int secTopY = ((wy >> 4) + 1) << 4;
					final int dyLimit = Math.min(SharedMemoryBridge.VOXEL_DIM, dy + (secTopY - wy));

					if (secIdx < 0 || secIdx >= sections.length) {
						dy = dyLimit;
						continue;
					}
					final LevelChunkSection section = sections[secIdx];
					if (section == null || section.hasOnlyAir()) {
						dy = dyLimit;
						continue;
					}

					for (; dy < dyLimit; dy++) {
						final int ly = (originY + dy) & 15;
						final int yOffset = dy << 12; // dy * 4096
						for (int dz = dzStart; dz < dzEnd; dz++) {
							final int lz = (originZ + dz) & 15;
							final int yzOffset = yOffset | (dz << 6); // + dz * 64
							for (int dx = dxStart; dx < dxEnd; dx++) {
								final int lx = (originX + dx) & 15;
								final BlockState state = section.getBlockState(lx, ly, lz);
								if (state != null && !state.isAir()) {
									stagingCells[yzOffset + dx] = encodeCached(state);
								}
							}
						}
					}
				}
			}
		}

		// Publish the entire 64x64x64 grid + origin atomically inside the seqlock (~20 us)
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
		updateCounter++;
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_UPDATE_COUNT, updateCounter);

		MemorySegment.copy(
			stagingCells,
			0,
			seg,
			SharedMemoryBridge.BYTE,
			SharedMemoryBridge.OFF_VOXEL_CELLS,
			stagingCells.length
		);

		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, seq + 2);
	}

	private static byte encodeCached(final BlockState state) {
		final Byte cached = STATE_CACHE.get(state);
		if (cached != null) {
			return cached.byteValue();
		}
		final byte computed = computeBlockStateCode(state);
		STATE_CACHE.put(state, computed);
		return computed;
	}

	public static byte encodeBlockState(final Level level, final BlockPos pos, final BlockState state) {
		if (state.isAir()) {
			return (byte) SharedMemoryBridge.VOX_AIR;
		}
		return encodeCached(state);
	}

	private static byte computeBlockStateCode(final BlockState state) {
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

		if (block instanceof SnowLayerBlock) {
			final int layers = state.hasProperty(SnowLayerBlock.LAYERS) ? state.getValue(SnowLayerBlock.LAYERS) : 1;
			if (layers < 4) {
				return (byte) SharedMemoryBridge.VOX_AIR;
			}
			if (layers < 8) {
				return (byte) (SharedMemoryBridge.VOX_SLAB_BOTTOM | flags);
			}
			return (byte) (SharedMemoryBridge.VOX_SOLID | flags);
		}

		final VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (collision.isEmpty()) {
			return (byte) SharedMemoryBridge.VOX_AIR;
		}
		final double maxY = collision.max(Direction.Axis.Y);
		if (maxY <= 0.35) {
			return (byte) SharedMemoryBridge.VOX_AIR;
		}
		if (maxY <= 0.5625) {
			return (byte) (SharedMemoryBridge.VOX_SLAB_BOTTOM | flags);
		}
		return (byte) (SharedMemoryBridge.VOX_SOLID | flags);
	}
}
