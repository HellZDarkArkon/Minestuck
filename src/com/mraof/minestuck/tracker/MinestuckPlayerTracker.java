package com.mraof.minestuck.tracker;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.network.FMLOutboundHandler;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.fml.relauncher.Side;

import com.mraof.minestuck.Minestuck;
import com.mraof.minestuck.MinestuckConfig;
import com.mraof.minestuck.editmode.ServerEditHandler;
import com.mraof.minestuck.inventory.captchalouge.CaptchaDeckHandler;
import com.mraof.minestuck.inventory.captchalouge.Modus;
import com.mraof.minestuck.network.CaptchaDeckPacket;
import com.mraof.minestuck.network.MinestuckChannelHandler;
import com.mraof.minestuck.network.MinestuckPacket;
import com.mraof.minestuck.network.MinestuckPacket.Type;
import com.mraof.minestuck.network.skaianet.SburbConnection;
import com.mraof.minestuck.network.skaianet.SkaianetHandler;
import com.mraof.minestuck.util.Debug;
import com.mraof.minestuck.util.GristHelper;
import com.mraof.minestuck.util.GristSet;
import com.mraof.minestuck.util.MinestuckPlayerData;
import com.mraof.minestuck.util.GristType;
import com.mraof.minestuck.util.Title;
import com.mraof.minestuck.util.TitleHelper;
import com.mraof.minestuck.util.UpdateChecker;
import com.mraof.minestuck.util.UsernameHandler;
import com.mraof.minestuck.world.storage.MinestuckSaveHandler;

public class MinestuckPlayerTracker {
	
	public static MinestuckPlayerTracker instance = new MinestuckPlayerTracker();
	
	@SubscribeEvent
	public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) 
	{
		EntityPlayer player = event.player;
		Debug.print(player.getName()+" joined the game. Sending packets.");
		MinecraftServer server = MinecraftServer.getServer();
		if(!server.isDedicatedServer() && UsernameHandler.host == null)
			UsernameHandler.host = event.player.getName();
		String encUsername = UsernameHandler.encode(player.getName());
		
		sendConfigPacket(player);
		
		SkaianetHandler.playerConnected(player.getName());
		
		if(MinestuckPlayerData.getGristSet(encUsername) == null)
		{
			Debug.printf("Grist set is null for player %s.", player.getName());
			MinestuckPlayerData.setGrist(encUsername, new GristSet(GristType.Build, 20));
		}
		
		if(CaptchaDeckHandler.getModus(player) == null)
		{
			if(MinestuckConfig.defaultModusType < 0)
			{
				CaptchaDeckHandler.ModusType[] list = MinestuckConfig.defaultModusType == -1 ? CaptchaDeckHandler.modusList : CaptchaDeckHandler.ModusType.values();
				int index = player.worldObj.rand.nextInt(list.length);
				Modus modus = list[index].createInstance();
				modus.player = player;
				modus.initModus(null, MinestuckConfig.defaultModusSize);
				CaptchaDeckHandler.setModus(player, modus);
			}
			else if(MinestuckConfig.defaultModusType >= 0 && MinestuckConfig.defaultModusType < CaptchaDeckHandler.ModusType.values().length)
			{
				Modus modus = CaptchaDeckHandler.ModusType.values()[MinestuckConfig.defaultModusType].createInstance();
				modus.player = player;
				modus.initModus(null, MinestuckConfig.defaultModusSize);
				CaptchaDeckHandler.setModus(player, modus);
			}
		}
		
		if(CaptchaDeckHandler.getModus(player) != null)
		{
			Modus modus = CaptchaDeckHandler.getModus(player);
			modus.player = player;
			MinestuckChannelHandler.sendToPlayer(MinestuckPacket.makePacket(Type.CAPTCHA, CaptchaDeckPacket.DATA, CaptchaDeckHandler.writeToNBT(modus)), player);
		}
		
		updateGristCache(UsernameHandler.encode(player.getName()));
		updateTitle(player);
		if(UpdateChecker.outOfDate)
			player.addChatMessage(new ChatComponentText("New version of Minestuck: " + UpdateChecker.latestVersion + "\nChanges: " + UpdateChecker.updateChanges));
	}
	
	@SubscribeEvent
	public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
		
		ServerEditHandler.onPlayerExit(event.player);
		Modus modus = CaptchaDeckHandler.getModus(event.player);
		if(modus != null)
			modus.player = null;
	}
	
	/**
	 * Uses an "encoded" username as parameter.
	 */
	public static void updateGristCache(String player) {

		int[] gristValues = new int[GristType.allGrists];
		for(int typeInt = 0; typeInt < gristValues.length; typeInt++)
			gristValues[typeInt] = GristHelper.getGrist(player,GristType.values()[typeInt]);

		//The player
		if(!player.equals(".client") || UsernameHandler.host != null) {
			EntityPlayerMP playerMP = MinecraftServer.getServer().getConfigurationManager().getPlayerByUsername(UsernameHandler.decode(player));
			if(playerMP != null) {
				MinestuckPacket packet = MinestuckPacket.makePacket(Type.GRISTCACHE, gristValues, false);
				MinestuckChannelHandler.sendToPlayer(packet, playerMP);
			}
		}

		//The editing player, if there is any.
		SburbConnection c = SkaianetHandler.getClientConnection(player);
		if(c != null && ServerEditHandler.getData(c) != null) {
			EntityPlayerMP editor = ServerEditHandler.getData(c).getEditor();
			MinestuckPacket packet = MinestuckPacket.makePacket(Type.GRISTCACHE, gristValues, true);
			MinestuckChannelHandler.sendToPlayer(packet, editor);
		}
	}
	
	public void updateTitle(EntityPlayer player)
	{
		String username = UsernameHandler.encode(player.getName());
		Title newTitle = MinestuckPlayerData.getTitle(username);
		if(newTitle == null)
			return;
		MinestuckPacket packet = MinestuckPacket.makePacket(Type.TITLE, newTitle.getHeroClass(), newTitle.getHeroAspect());
		MinestuckChannelHandler.sendToPlayer(packet, player);
	}
	public static void updateLands(EntityPlayer player)
	{
		MinestuckPacket packet = MinestuckPacket.makePacket(Type.LANDREGISTER);
		Debug.printf("Sending land packets to %s.", player == null ? "all players" : player.getName());
		if(player == null)
			MinestuckChannelHandler.sendToAllPlayers(packet);
		else
			MinestuckChannelHandler.sendToPlayer(packet, player);
	}
	public static void updateLands()
	{
		updateLands(null);
	}

	public static void sendConfigPacket(EntityPlayer player) {
		MinestuckPacket packet = MinestuckPacket.makePacket(Type.CONFIG);
		MinestuckChannelHandler.sendToPlayer(packet, player);
	}
	
}
