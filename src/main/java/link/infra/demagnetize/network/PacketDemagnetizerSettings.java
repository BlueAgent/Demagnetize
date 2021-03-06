package link.infra.demagnetize.network;

import link.infra.demagnetize.blocks.DemagnetizerTileEntity;
import link.infra.demagnetize.blocks.DemagnetizerTileEntity.RedstoneStatus;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.network.NetworkEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Supplier;

public class PacketDemagnetizerSettings {
	private static final Logger LOGGER = LogManager.getLogger();
	
	private int range;
	private RedstoneStatus redstoneSetting = RedstoneStatus.REDSTONE_DISABLED;
	private boolean whitelist;
	private BlockPos demagnetizerBlockPos;

	private PacketDemagnetizerSettings() {
		// for server initialisation
	}

	public PacketDemagnetizerSettings(int range, RedstoneStatus redstoneSetting, boolean whitelist, BlockPos demagnetizerBlockPos) {
		this.range = range;
		this.redstoneSetting = redstoneSetting;
		this.whitelist = whitelist;
		this.demagnetizerBlockPos = demagnetizerBlockPos;
	}

	void encode(PacketBuffer buf) {
		buf.writeInt(range);
		buf.writeChar(redstoneSetting.getNum());
		buf.writeBoolean(whitelist);
		buf.writeBlockPos(demagnetizerBlockPos);
	}

	static PacketDemagnetizerSettings decode(PacketBuffer buf) {
		PacketDemagnetizerSettings p = new PacketDemagnetizerSettings();
		p.range = buf.readInt();
		p.redstoneSetting = RedstoneStatus.parse(buf.readChar());
		if (p.redstoneSetting == null) {
			p.redstoneSetting = RedstoneStatus.REDSTONE_DISABLED;
		}
		p.whitelist = buf.readBoolean();
		p.demagnetizerBlockPos = buf.readBlockPos();
		return p;
	}

	void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			ServerPlayerEntity playerEntity = ctx.get().getSender();
			if (playerEntity == null) return;
			World world = playerEntity.getEntityWorld();

			if (world.isAreaLoaded(demagnetizerBlockPos, 1)) {
				TileEntity te = world.getTileEntity(demagnetizerBlockPos);
				if (te instanceof DemagnetizerTileEntity) {
					DemagnetizerTileEntity demagTE = (DemagnetizerTileEntity) te;
					demagTE.setRange(range);
					demagTE.setRedstoneSetting(redstoneSetting);
					demagTE.setWhitelist(whitelist);
					demagTE.updateBlock();
				} else {
					LOGGER.warn("Player tried to change settings of something that isn't a demagnetizer (or doesn't have a TE)!");
				}
			}
		});
		ctx.get().setPacketHandled(true);
	}
}
