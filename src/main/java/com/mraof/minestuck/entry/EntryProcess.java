package com.mraof.minestuck.entry;

import com.mraof.minestuck.MinestuckConfig;
import com.mraof.minestuck.block.GateBlock;
import com.mraof.minestuck.block.MSBlocks;
import com.mraof.minestuck.computer.editmode.ServerEditHandler;
import com.mraof.minestuck.player.IdentifierHandler;
import com.mraof.minestuck.player.PlayerIdentifier;
import com.mraof.minestuck.skaianet.SburbConnection;
import com.mraof.minestuck.skaianet.SkaianetHandler;
import com.mraof.minestuck.skaianet.TitleSelectionHook;
import com.mraof.minestuck.tileentity.ComputerTileEntity;
import com.mraof.minestuck.tileentity.GateTileEntity;
import com.mraof.minestuck.tileentity.TransportalizerTileEntity;
import com.mraof.minestuck.util.Teleport;
import com.mraof.minestuck.world.GateHandler;
import com.mraof.minestuck.world.MSDimensions;
import com.mraof.minestuck.world.storage.MSExtraData;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.Util;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.IWorld;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.gen.Heightmap;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.Constants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class EntryProcess
{
	private static final Logger LOGGER = LogManager.getLogger();
	
	private static final Set<EntryBlockProcessing> blockProcessors = new HashSet<>();
	
	/**
	 * Not thread-safe. Make sure to only call this on the main thread
	 */
	public static void addBlockProcessing(EntryBlockProcessing processing)
	{
		blockProcessors.add(processing);
	}
	
	private final int artifactRange = MinestuckConfig.SERVER.artifactRange.get();
	
	private int xDiff;
	private int yDiff;
	private int zDiff;
	private int topY;
	private BlockPos origin;
	private boolean creative;
	private HashSet<BlockMove> blockMoves;
	
	public void onArtifactActivated(ServerPlayerEntity player)
	{
		try
		{
			if(player.level.dimension() != World.NETHER)
			{
				if(!TitleSelectionHook.performEntryCheck(player))
					return;
				
				PlayerIdentifier identifier = IdentifierHandler.encode(player);
				Optional<SburbConnection> c = SkaianetHandler.get(player.level).getPrimaryConnection(identifier, true);
				
				//Only performs Entry if you have no connection, haven't Entered, or you're not in a Land and additional Entries are permitted.
				if(!c.isPresent() || !c.get().hasEntered() || !MinestuckConfig.SERVER.stopSecondEntry.get() && !MSDimensions.isLandDimension(player.server, player.level.dimension()))
				{
					if(!canModifyEntryBlocks(player.level, player))
					{
						player.sendMessage(new StringTextComponent("You are not allowed to enter here."), Util.NIL_UUID);	//TODO translation key
						return;
					}
					
					if(c.isPresent() && c.get().hasEntered())
					{
						ServerWorld landWorld = Objects.requireNonNull(player.getServer()).getLevel(c.get().getClientDimension());
						if(landWorld == null)
						{
							return;
						}
						
						//Teleports the player to their home in the Medium, without any bells or whistles.
						BlockPos pos = new BlockPos(0, 100, 0);//landWorld.getDimension().getSpawnPoint(); TODO
						Teleport.teleportEntity(player, landWorld, pos.getX() + 0.5F, pos.getY(), pos.getZ() + 0.5F, player.yRot, player.xRot);
						
						return;
					}
					
					RegistryKey<World> landDimension = SkaianetHandler.get(player.level).prepareEntry(identifier);
					if(landDimension == null)
					{
						player.sendMessage(new StringTextComponent("Something went wrong while creating your Land. More details in the server console."), Util.NIL_UUID);
					}
					else
					{
						ServerWorld oldWorld = (ServerWorld) player.level;
						ServerWorld newWorld = Objects.requireNonNull(player.getServer()).getLevel(landDimension);
						if(newWorld == null)
						{
							return;
						}
						
						if(this.prepareDestination(player.blockPosition(), player, oldWorld))
						{
							moveBlocks(oldWorld, newWorld);
							if(Teleport.teleportEntity(player, newWorld) != null)
							{
								finalizeDestination(player, oldWorld, newWorld);
								SkaianetHandler.get(player.level).onEntry(identifier);
							} else
							{
								player.sendMessage(new StringTextComponent("Entry failed. Unable to teleport you!"), Util.NIL_UUID);
							}
						}
					}
				}
			}
		} catch(Exception e)
		{
			LOGGER.error("Exception when {} tried to enter their land.", player.getName().getString(), e);
			player.sendMessage(new StringTextComponent("[Minestuck] Something went wrong during entry. "+ (player.getServer().isDedicatedServer()?"Check the console for the error message.":"Notify the server owner about this.")).withStyle(TextFormatting.RED), Util.NIL_UUID);
		}
	}
	
	private boolean prepareDestination(BlockPos origin, ServerPlayerEntity player, ServerWorld worldserver0)
	{
		
		blockMoves = new HashSet<>();
		
		LOGGER.info("Starting entry for player {}", player.getName().getString());
		int x = origin.getX();
		int y = origin.getY();
		int z = origin.getZ();
		this.origin = origin;
		
		creative = player.gameMode.isCreative();
		
		topY = MinestuckConfig.SERVER.adaptEntryBlockHeight.get() ? getTopHeight(worldserver0, x, y, z) : y + artifactRange;
		yDiff = 127 - topY;
		xDiff = 0 - x;
		zDiff = 0 - z;
		
		LOGGER.debug("Loading block movements...");
		long time = System.currentTimeMillis();
		int bl = 0;
		boolean foundComputer = false;
		for(int blockX = x - artifactRange; blockX <= x + artifactRange; blockX++)
		{
			int zWidth = (int) Math.sqrt((artifactRange+0.5) * (artifactRange+0.5) - (blockX - x) * (blockX - x));
			for(int blockZ = z - zWidth; blockZ <= z + zWidth; blockZ++)
			{
				Chunk c = worldserver0.getChunk(blockX >> 4, blockZ >> 4);
				
				int height = (int) Math.sqrt(artifactRange * artifactRange - (((blockX - x) * (blockX - x) + (blockZ - z) * (blockZ - z)) / 2F));
				
				int blockY;
				for(blockY = Math.max(0, y - height); blockY <= Math.min(topY, y + height); blockY++)
				{
					BlockPos pos = new BlockPos(blockX, blockY, blockZ);
					BlockPos pos1 = pos.offset(xDiff, yDiff, zDiff);
					BlockState block = worldserver0.getBlockState(pos);
					TileEntity te = worldserver0.getBlockEntity(pos);
					
					Block gotBlock = block.getBlock();
					
					if(gotBlock == Blocks.BEDROCK || gotBlock == Blocks.NETHER_PORTAL)
					{
						blockMoves.add(new BlockMove(c, pos, pos1, Blocks.AIR.defaultBlockState(), true));
						continue;
					}
					else if(!creative && (gotBlock == Blocks.COMMAND_BLOCK || gotBlock == Blocks.CHAIN_COMMAND_BLOCK || gotBlock == Blocks.REPEATING_COMMAND_BLOCK))
					{
						player.displayClientMessage(new StringTextComponent("You are not allowed to move command blocks."), false);
						return false;
					} else if(te instanceof ComputerTileEntity)		//If the block is a computer
					{
						if(!((ComputerTileEntity)te).owner.equals(IdentifierHandler.encode((PlayerEntity) player)))	//You can't Enter with someone else's computer
						{
							player.displayClientMessage(new StringTextComponent("You are not allowed to move other players' computers."), false);
							return false;
						}
						
						foundComputer = true;	//You have a computer in range. That means you're taking your computer with you when you Enter. Smart move.
					}
					
					//Shouldn't this line check if the block is an edge block?
					blockMoves.add(new BlockMove(c, pos, pos1, block, false));
				}
				
				//What does this code accomplish?
				for(blockY += yDiff; blockY <= 255; blockY++)
				{
					//The first BlockPos isn't used for this operation.
					blockMoves.add(new BlockMove(c, BlockPos.ZERO, new BlockPos(blockX + xDiff, blockY, blockZ + zDiff), Blocks.AIR.defaultBlockState(), false));
				}
			}
		}
		
		if(!foundComputer && MinestuckConfig.SERVER.needComputer.get())
		{
			player.displayClientMessage(new StringTextComponent("There is no computer in range."), false);
			return false;
		}
		
		return true;
	}
	
	private void moveBlocks(ServerWorld worldserver0, ServerWorld worldserver1)
	{
		//This is split into two sections because moves that require block updates should happen after the ones that don't.
		//This helps to ensure that "anchored" blocks like torches still have the blocks they are anchored to when they update.
		//Some blocks like this (confirmed for torches, rails, and glowystone) will break themselves if they update without their anchor.
		LOGGER.debug("Moving blocks...");
		HashSet<BlockMove> blockMoves2 = new HashSet<>();
		for(BlockMove move : blockMoves)
		{
			if(move.update)
				move.copy(worldserver1, worldserver1.getChunk(move.dest));
			else
				blockMoves2.add(move);
		}
		for(BlockMove move : blockMoves2)
		{
			move.copy(worldserver1, worldserver1.getChunk(move.dest));
		}
		blockMoves2.clear();
	}
	
	private void finalizeDestination(Entity player, ServerWorld worldserver0, ServerWorld worldserver1)
	{
		if(player instanceof ServerPlayerEntity)
		{
			int x = origin.getX();
			int y = origin.getY();
			int z = origin.getZ();
			
			LOGGER.debug("Teleporting entities...");
			//The fudge here is to ensure that the AABB will always contain every entity meant to be moved.
			// As entities outside the radius will be excluded from transport anyway, this is fine.
			AxisAlignedBB entityTeleportBB = player.getBoundingBox().inflate(artifactRange + 0.5);
			List<Entity> list = worldserver0.getEntities(player, entityTeleportBB);
			Iterator<Entity> iterator = list.iterator();
			while (iterator.hasNext())
			{
				Entity e = iterator.next();
				if(origin.distSqr(e.getX(), e.getY(), e.getZ(), true) <= artifactRange*artifactRange)
				{
					if(MinestuckConfig.SERVER.entryCrater.get() || e instanceof PlayerEntity || !creative && e instanceof ItemEntity)
					{
						if(e instanceof PlayerEntity && ServerEditHandler.getData((PlayerEntity) e) != null)
							ServerEditHandler.reset(ServerEditHandler.getData((PlayerEntity) e));
						else
						{
							Teleport.teleportEntity(e, worldserver1, e.getX() + xDiff, e.getY() + yDiff, e.getZ() + zDiff);
						}
						//These entities should no longer be in the world, and this list is later used for entities that *should* remain.
						iterator.remove();
					}
					else	//Copy instead of teleport
					{
						Entity newEntity = e.getType().create(worldserver1);
						if (newEntity != null)
						{
							newEntity.restoreFrom(e);
							newEntity.setPos(newEntity.getX() + xDiff, newEntity.getY() + yDiff, newEntity.getZ() + zDiff);
							worldserver1.addFreshEntity(newEntity);
						}
					}
				}
			}
			
			LOGGER.debug("Removing original blocks");
			for(BlockMove move : blockMoves)
			{
				removeTileEntity(worldserver0, move.source, creative);	//Tile entities need special treatment
				
				if(MinestuckConfig.SERVER.entryCrater.get() && worldserver0.getBlockState(move.source).getBlock() != Blocks.BEDROCK)
				{
					if(move.update)
						worldserver0.setBlock(move.source, Blocks.AIR.defaultBlockState(), Constants.BlockFlags.DEFAULT);
					else
						worldserver0.setBlock(move.source, Blocks.AIR.defaultBlockState(), Constants.BlockFlags.BLOCK_UPDATE);
				}
			}
			blockMoves.clear();
			
			player.teleportTo(player.getX() + xDiff, player.getY() + yDiff, player.getZ() + zDiff);
			
			//Remove entities that were generated in the process of teleporting entities and removing blocks.
			// This is usually caused by "anchored" blocks being updated between the removal of their anchor and their own removal.
			if(!creative || MinestuckConfig.SERVER.entryCrater.get())
			{
				LOGGER.debug("Removing entities left in the crater...");
				List<Entity> removalList = worldserver0.getEntities(player, entityTeleportBB);
				
				//We check if the old list contains the entity, because that means it was there before the entities were teleported and blocks removed.
				// This can be caused by them being outside the Entry radius but still within the AABB,
				// Or by the player being in creative mode, or having entryCrater disabled, etc.
				// Ultimately, this means that the entity has already been taken care of as much as it needs to be, and it is inappropriate to remove the entity.
				removalList.removeAll(list);
				
				iterator = removalList.iterator();
				if(MinestuckConfig.SERVER.entryCrater.get())
				{
					while (iterator.hasNext())
					{
						iterator.next().remove();
					}
				} else
				{
					while (iterator.hasNext())
					{
						Entity e = iterator.next();
						if(e instanceof ItemEntity)
							e.remove();
					}
				}
			}
			
			LOGGER.debug("Placing gates...");
			placeGates(worldserver1);
			
			MSExtraData.get(worldserver1).addPostEntryTask(new PostEntryTask(worldserver1.dimension(), x + xDiff, y + yDiff, z + zDiff, artifactRange, (byte) 0));
			
			MSDimensions.getLandInfo(worldserver1).setSpawn(MathHelper.floor(player.getY()));
			
			LOGGER.info("Entry finished");
		}
	}
	
	/**
	 * Determines if it is appropriate to remove the tile entity in the specified location,
	 * and removes both the tile entity and its corresponding block if so.
	 * This method is expressly designed to prevent drops from appearing when the block is removed.
	 * It will also deliberately trigger block updates based on the removal of the tile entity's block.
	 * @param worldserver0 The world where the tile entity is located
	 * @param pos The position at which the tile entity is located
	 * @param creative Whether or not creative-mode rules should be employed
	 */
	private static void removeTileEntity(ServerWorld worldserver0, BlockPos pos, boolean creative)
	{
		TileEntity tileEntity = worldserver0.getBlockEntity(pos);
		if(tileEntity != null)
		{
			if(MinestuckConfig.SERVER.entryCrater.get() || !creative)
			{
				String name = worldserver0.getBlockState(pos).getBlock().getRegistryName().toString();
				try {
					worldserver0.removeBlockEntity(pos);
					worldserver0.removeBlock(pos, true);
				} catch (Exception e) {
					LOGGER.warn("Exception encountered when removing tile entity " + name + " during entry:", e);
				}
			} else
			{
				if(tileEntity instanceof ComputerTileEntity)	//Avoid duplicating computer data when a computer is kept in the overworld
					((ComputerTileEntity) tileEntity).programData = new CompoundNBT();
				else if(tileEntity instanceof TransportalizerTileEntity)
					worldserver0.removeBlockEntity(pos);
			}
		}
	}
	
	private boolean canModifyEntryBlocks(World world, PlayerEntity player)
	{
		int x = (int) player.getX();
		if(player.getX() < 0) x--;
		int y = (int) player.getY();
		int z = (int) player.getZ();
		if(player.getZ() < 0) z--;
		for(int blockX = x - artifactRange; blockX <= x + artifactRange; blockX++)
		{
			int zWidth = (int) Math.sqrt(artifactRange * artifactRange - (blockX - x) * (blockX - x));
			for(int blockZ = z - zWidth; blockZ <= z + zWidth; blockZ++)
				if(!world.mayInteract(player, new BlockPos(blockX, y, blockZ)))
					return false;
		}
		
		return true;
	}
	
	private static void copyBlockDirect(IWorld world, IChunk cSrc, IChunk cDst, int xSrc, int ySrc, int zSrc, int xDst, int yDst, int zDst)
	{
		BlockPos dest = new BlockPos(xDst, yDst, zDst);
		ChunkSection blockStorageSrc = getBlockStorage(cSrc, ySrc >> 4);
		ChunkSection blockStorageDst = getBlockStorage(cDst, yDst >> 4);
		int y = yDst;
		xSrc &= 15; ySrc &= 15; zSrc &= 15; xDst &= 15; yDst &= 15; zDst &= 15;
		
		boolean isEmpty = blockStorageDst.isEmpty();
		BlockState state = blockStorageSrc.getBlockState(xSrc, ySrc, zSrc);
		blockStorageDst.setBlockState(xDst, yDst, zDst, state);
		if(isEmpty != blockStorageDst.isEmpty())
			world.getChunkSource().getLightEngine().updateSectionStatus(dest, blockStorageDst.isEmpty());	//I assume this adds or removes a light storage section here depending on if it is needed (because a section with just air doesn't have to be regarded)
		
		cDst.getOrCreateHeightmapUnprimed(Heightmap.Type.MOTION_BLOCKING).update(xDst, y, zDst, state);
		cDst.getOrCreateHeightmapUnprimed(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES).update(xDst, y, zDst, state);
		cDst.getOrCreateHeightmapUnprimed(Heightmap.Type.OCEAN_FLOOR).update(xDst, y, zDst, state);
		cDst.getOrCreateHeightmapUnprimed(Heightmap.Type.WORLD_SURFACE).update(xDst, y, zDst, state);
	}
	
	private static ChunkSection getBlockStorage(IChunk c, int y)
	{
		ChunkSection section = c.getSections()[y];
		if(section == Chunk.EMPTY_SECTION)
			section = c.getSections()[y] = new ChunkSection(y << 4);
		return section;
	}
	
	/**
	 * Gives the Y-value of the highest non-air block within artifact range of the coordinates provided in the given world.
	 */
	private int getTopHeight(ServerWorld world, int x, int y, int z)
	{
		LOGGER.debug("Getting maxY..");
		int maxY = y;
		for(int blockX = x - artifactRange; blockX <= x + artifactRange; blockX++)
		{
			int zWidth = (int) Math.sqrt(artifactRange * artifactRange - (blockX - x) * (blockX - x));
			for(int blockZ = z - zWidth; blockZ <= z + zWidth; blockZ++)
			{
				int height = (int) (Math.sqrt(artifactRange * artifactRange - (((blockX - x) * (blockX - x) + (blockZ - z) * (blockZ - z)) / 2F)));
				for(int blockY = Math.min(255, y + height); blockY > maxY; blockY--)
					if(!world.isEmptyBlock(new BlockPos(blockX, blockY, blockZ)))
					{
						maxY = blockY;
						break;
					}
			}
		}
		
		LOGGER.debug("maxY: {}", maxY);
		return maxY;
	}
	
	public static void placeGates(ServerWorld world)
	{
		placeGate(GateHandler.Type.GATE_1, new BlockPos(0, GateHandler.gateHeight1, 0), world);
		placeGate(GateHandler.Type.GATE_2, new BlockPos(0, GateHandler.gateHeight2, 0), world);
	}
	
	private static void placeGate(GateHandler.Type gateType, BlockPos pos, ServerWorld world)
	{
		for(int i = 0; i < 9; i++)
			if(i == 4)
			{
				world.setBlock(pos, MSBlocks.GATE.defaultBlockState().cycle(GateBlock.MAIN), 0);
				GateTileEntity tileEntity = (GateTileEntity) world.getBlockEntity(pos);
				tileEntity.gateType = gateType;
			}
			else world.setBlock(pos.offset((i % 3) - 1, 0, i/3 - 1), MSBlocks.GATE.defaultBlockState(), 0);
	}
	
	private static class BlockMove
	{
		private final Chunk chunkFrom;
		private final BlockPos source;
		private final BlockPos dest;
		private final BlockState block;
		private final boolean update;
		
		BlockMove(Chunk c, BlockPos src, BlockPos dst, BlockState b, boolean u)
		{
			chunkFrom = c;
			source = src;
			dest = dst;
			block = b;
			update = u;
		}
		
		void copy(ServerWorld world, IChunk chunkTo)
		{
			if(chunkTo.getBlockState(dest).getBlock() == Blocks.BEDROCK)
			{
				return;
			}
			
			if(update)
			{
				chunkTo.setBlockState(dest, block, true);
			} else if(block == Blocks.AIR.defaultBlockState())
			{
				world.setBlock(dest, block, 0);
			} else
			{
				copyBlockDirect(world, chunkFrom, chunkTo, source.getX(), source.getY(), source.getZ(), dest.getX(), dest.getY(), dest.getZ());
			}
			
			TileEntity tileEntity = chunkFrom.getBlockEntity(source, Chunk.CreateEntityType.CHECK);
			TileEntity newTE = null;
			if(tileEntity != null)
			{
				CompoundNBT nbt = new CompoundNBT();
				tileEntity.save(nbt);
				nbt.putInt("x", dest.getX());
				nbt.putInt("y", dest.getY());
				nbt.putInt("z", dest.getZ());
				newTE = TileEntity.loadStatic(block, nbt);
				if(newTE != null)
					world.setBlockEntity(dest, newTE);
				else LOGGER.warn("Unable to create a new tile entity {} when teleporting blocks to the medium!", tileEntity.getType().getRegistryName());
				
			}
			
			for(EntryBlockProcessing processing : blockProcessors)
			{
				processing.copyOver((ServerWorld) chunkFrom.getLevel(), source, world, dest, block, tileEntity, newTE);
			}
		}
	}
}