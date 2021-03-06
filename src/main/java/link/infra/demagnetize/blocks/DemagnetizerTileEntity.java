package link.infra.demagnetize.blocks;

import link.infra.demagnetize.ConfigHandler;
import link.infra.demagnetize.network.PacketDemagnetizerSettings;
import link.infra.demagnetize.network.PacketHandler;
import net.minecraft.block.BlockState;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SUpdateTileEntityPacket;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class DemagnetizerTileEntity extends TileEntity implements ITickableTileEntity, INamedContainerProvider {

	public enum RedstoneStatus {
		REDSTONE_DISABLED(0), POWERED(1), UNPOWERED(2);

		private final int num;
		RedstoneStatus(int num) {
			this.num = num;
		}

		public int getNum() {
			return num;
		}

		public static RedstoneStatus parse(int num) {
			for (RedstoneStatus s : RedstoneStatus.values()) {
				if (s.getNum() == num) {
					return s;
				}
			}
			return null;
		}
	}

	private AxisAlignedBB scanArea;
	private int range;
	private RedstoneStatus redstoneSetting = RedstoneStatus.REDSTONE_DISABLED;
	private boolean filtersWhitelist = false; // Default to using blacklist
	private boolean isPowered = false;

	final boolean advanced;

	public DemagnetizerTileEntity(boolean advanced) {
		super(advanced ? ModBlocks.DEMAGNETIZER_ADVANCED_TILE_ENTITY : ModBlocks.DEMAGNETIZER_TILE_ENTITY);
		this.advanced = advanced;

		range = getMaxRange();

		updateBoundingBox();
		DemagnetizerEventHandler.addTileEntity(this);

		itemStackHandler = new ItemStackHandler(getFilterSize()) {
			@Override
			protected void onContentsChanged(int slot) {
				DemagnetizerTileEntity.this.markDirty();
			}

			@Override
			public int getSlotLimit(int slot) {
				return 1; // Only allow one item
			}

			@Nonnull
			@Override
			public ItemStack extractItem(int slot, int amount, boolean simulate) {
				this.stacks.set(slot, ItemStack.EMPTY);
				return ItemStack.EMPTY; // Extract "failed", no increment of stack size (ghost item)
			}
		};
	}

	int getMaxRange() {
		return advanced ? ConfigHandler.DEMAGNETIZER_ADVANCED_RANGE.get() : ConfigHandler.DEMAGNETIZER_RANGE.get();
	}

	private void updateBoundingBox() {
		scanArea = new AxisAlignedBB(getPos()).grow(range);
	}

	// Ensure that the new bounding box is updated
	@Override
	public void setPos(@Nonnull BlockPos pos) {
		super.setPos(pos);
		updateBoundingBox();
	}

	// Ensure that the new bounding box is updated
	@Override
	public void setWorldAndPos(@Nonnull World world, @Nonnull BlockPos pos) {
		super.setWorldAndPos(world, pos);
		updateBoundingBox();
	}

	@Override
	public void read(@Nonnull BlockState state, CompoundNBT compound) {
		if (compound.contains("redstone")) {
			try {
				redstoneSetting = RedstoneStatus.valueOf(compound.getString("redstone"));
			} catch (IllegalArgumentException e) {
				// Ignore
			}
		}
		if (compound.contains("range")) {
			int pendingRange = compound.getInt("range");
			if (pendingRange <= getMaxRange() && pendingRange > 0) {
				range = pendingRange;
			}
		}
		if (getFilterSize() == 0) {
			filtersWhitelist = false;
		} else {
			if (compound.contains("whitelist")) {
				filtersWhitelist = compound.getBoolean("whitelist");
			}
			if (compound.contains("items")) {
				CompoundNBT itemsTag = compound.getCompound("items");
				// Reset the filter size in NBT, in case config changes
				itemsTag.putInt("Size", getFilterSize());
				itemStackHandler.deserializeNBT(itemsTag);
			}
		}
		if (compound.contains("redstonePowered")) {
			isPowered = compound.getBoolean("redstonePowered");
		}
		super.read(state, compound);
		// super.read could update TE pos
		updateBoundingBox();
	}

	@Nonnull
	@Override
	public CompoundNBT write(CompoundNBT compound) {
		compound.put("items", itemStackHandler.serializeNBT());
		compound.putString("redstone", redstoneSetting.name());
		compound.putInt("range", range);
		compound.putBoolean("whitelist", filtersWhitelist);
		compound.putBoolean("redstonePowered", isPowered);
		return super.write(compound);
	}

	@Nonnull
	@Override
	public CompoundNBT getUpdateTag() {
		return write(new CompoundNBT());
	}

	@Override
	public SUpdateTileEntityPacket getUpdatePacket() {
		CompoundNBT nbtTag = new CompoundNBT();
		this.write(nbtTag);
		return new SUpdateTileEntityPacket(getPos(), 1, nbtTag);
	}

	@Override
	public void onDataPacket(NetworkManager net, SUpdateTileEntityPacket packet) {
		this.read(getBlockState(), packet.getNbtCompound());
	}

	// Only check for items every 4 ticks
	private final int tickTime = 4;
	private int currTick = tickTime;

	@Override
	public void tick() {
		// Remove te if it has been destroyed
		if (isRemoved() || !hasWorld()) {
			DemagnetizerEventHandler.removeTileEntity(this);
			return;
		}

		if (redstoneUnpowered()) {
			// Reset tick time when redstone disabled
			currTick = tickTime;
			return;
		}

		if (currTick == tickTime) {
			assert world != null;
			List<ItemEntity> list = world.getEntitiesWithinAABB(ItemEntity.class, scanArea);
			for (ItemEntity item : list) {
				if (!checkItemFilter(item)) {
					continue;
				}
				demagnetizeItem(item);
			}

			currTick = 0;
		} else {
			currTick++;
		}
	}

	boolean checkItem(ItemEntity item) {
		if (redstoneUnpowered()) {
			return false;
		}

		if (scanArea != null && item != null) {
			AxisAlignedBB entityBox = item.getBoundingBox();
			return scanArea.intersects(entityBox) && checkItemFilter(item);
		} else {
			return false;
		}
	}

	/**
	 * Equivalent to checkItem but called on the client when the item is empty (hasn't received metadata yet)
	 */
	boolean checkItemClientPreMetadata(ItemEntity item) {
		if (redstoneUnpowered()) {
			return false;
		}

		if (scanArea != null && item != null) {
			// Can't use checkItemFilter yet, so queue checking for the next TE tick
			return scanArea.intersects(item.getBoundingBox());
		}
		return false;
	}

	void demagnetizeItem(ItemEntity item) {
		CompoundNBT data = item.getPersistentData();
		if (!data.getBoolean("PreventRemoteMovement")) {
			data.putBoolean("PreventRemoteMovement", true);
		}
		// Allow machines to remotely move items
		if (!data.getBoolean("AllowMachineRemoteMovement")) {
			data.putBoolean("AllowMachineRemoteMovement", true);
		}
	}

	public void updateBlock() {
		markDirty();
		if (world != null) {
			BlockState state = world.getBlockState(getPos());
			world.notifyBlockUpdate(getPos(), state, state, 3);
		}
	}
	
	void updateRedstone(boolean redstoneStatus) {
		isPowered = redstoneStatus;
		updateBlock();
	}

	private boolean redstoneUnpowered() {
		switch (redstoneSetting) {
		case POWERED:
			return !isPowered;
		case UNPOWERED:
			return isPowered;
		default:
			return false;
		}
	}

	final ItemStackHandler itemStackHandler;

	int getFilterSize() {
		return advanced ? ConfigHandler.DEMAGNETIZER_ADVANCED_FILTER_SLOTS.get() : ConfigHandler.DEMAGNETIZER_FILTER_SLOTS.get();
	}

	private boolean checkItemFilter(ItemEntity item) {
		// Client event gives empty itemstack, cannot be compared so must ignore
		if (item.getItem().isEmpty()) {
			// If filter is empty and whitelist is disabled, can safely demagnetize
			if (!filtersWhitelist) {
				for (int i = 0; i < itemStackHandler.getSlots(); i++) {
					if (!itemStackHandler.getStackInSlot(i).isEmpty()) {
						return false;
					}
				}
				return true;
			}
			return false;
		}
		
		if (filtersWhitelist) {
			return checkItemFilterMatches(item);
		} else {
			return !checkItemFilterMatches(item);
		}
	}

	private boolean checkItemFilterMatches(ItemEntity item) {
		ItemStack matchingItem = item.getItem();
		for (int i = 0; i < itemStackHandler.getSlots(); i++) {
			// If the current slot index >= filter size, return and ignore future slots
			if (i >= getFilterSize()) {
				return false;
			}
			
			ItemStack filterStack = itemStackHandler.getStackInSlot(i);
			if (filterStack.isEmpty()) {
				continue;
			}
			if (filterStack.isItemEqualIgnoreDurability(matchingItem)) {
				return true;
			}
		}
		return false;
	}
	
	int getRange() {
		if (range > getMaxRange()) {
			range = getMaxRange();
		}
		if (range < 1) {
			range = 0;
		}
		return range;
	}
	
	RedstoneStatus getRedstoneSetting() {
		return redstoneSetting;
	}
	
	boolean isWhitelist() {
		return filtersWhitelist;
	}
	
	public void setRange(int range) {
		this.range = range;
		updateBoundingBox();
	}
	
	public void setRedstoneSetting(RedstoneStatus setting) {
		this.redstoneSetting = setting;
	}
	
	public void setWhitelist(boolean whitelist) {
		if (getFilterSize() > 0) {
			this.filtersWhitelist = whitelist;
		} else {
			this.filtersWhitelist = false;
		}
	}
	
	void sendSettingsToServer() {
		PacketHandler.INSTANCE.sendToServer(new PacketDemagnetizerSettings(range, redstoneSetting, filtersWhitelist, getPos()));
	}

	@Nonnull
	@Override
	public ITextComponent getDisplayName() {
		if (advanced) {
			return new TranslationTextComponent(ModBlocks.DEMAGNETIZER_ADVANCED.getTranslationKey());
		} else {
			return new TranslationTextComponent(ModBlocks.DEMAGNETIZER.getTranslationKey());
		}
	}

	@Nullable
	@Override
	public Container createMenu(int i, @Nonnull PlayerInventory playerInventory, @Nonnull PlayerEntity playerEntity) {
		assert world != null;
		return new DemagnetizerContainer(i, world, pos, playerInventory);
	}

}
