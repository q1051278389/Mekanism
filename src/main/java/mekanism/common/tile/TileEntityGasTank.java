package mekanism.common.tile;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;

import mekanism.api.Coord4D;
import mekanism.api.EnumColor;
import mekanism.api.gas.Gas;
import mekanism.api.gas.GasRegistry;
import mekanism.api.gas.GasStack;
import mekanism.api.gas.GasTank;
import mekanism.api.gas.GasTransmission;
import mekanism.api.gas.IGasHandler;
import mekanism.api.gas.IGasItem;
import mekanism.api.gas.ITubeConnection;
import mekanism.api.transmitters.TransmissionType;
import mekanism.common.Mekanism;
import mekanism.common.SideData;
import mekanism.common.base.IEjector;
import mekanism.common.base.IRedstoneControl;
import mekanism.common.base.ISideConfiguration;
import mekanism.common.network.PacketTileEntity.TileEntityMessage;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.util.InventoryUtils;
import mekanism.common.util.MekanismUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.MathHelper;
import net.minecraft.util.EnumFacing;

public class TileEntityGasTank extends TileEntityContainerBlock implements IGasHandler, ITubeConnection, IRedstoneControl, ISideConfiguration
{
	public enum GasMode
	{
		IDLE,
		DUMPING,
		DUMPING_EXCESS
	}

	/** The type of gas stored in this tank. */
	public GasTank gasTank = new GasTank(MAX_GAS);

	public static final int MAX_GAS = 96000;

	/** How fast this tank can output gas. */
	public int output = 256;

	public GasMode dumping;

	public int currentGasAmount;

	/** This machine's current RedstoneControl type. */
	public RedstoneControl controlType;
	
	public TileComponentEjector ejectorComponent;
	public TileComponentConfig configComponent;

	public TileEntityGasTank()
	{
		super("GasTank");
		
		configComponent = new TileComponentConfig(this, TransmissionType.ITEM, TransmissionType.GAS);
		
		configComponent.addOutput(TransmissionType.ITEM, new SideData("None", EnumColor.GREY, InventoryUtils.EMPTY));
		configComponent.addOutput(TransmissionType.ITEM, new SideData("Fill", EnumColor.DARK_BLUE, new int[] {0}));
		configComponent.addOutput(TransmissionType.ITEM, new SideData("Empty", EnumColor.DARK_RED, new int[] {1}));
		
		configComponent.setConfig(TransmissionType.ITEM, new byte[] {2, 1, 0, 0, 0, 0});
		configComponent.setCanEject(TransmissionType.ITEM, false);
		configComponent.setIOConfig(TransmissionType.GAS);
		configComponent.setEjecting(TransmissionType.GAS, true);
		
		inventory = new ItemStack[2];
		dumping = GasMode.IDLE;
		controlType = RedstoneControl.DISABLED;
		
		ejectorComponent = new TileComponentEjector(this);
	}

	@Override
	public void onUpdate()
	{
		if(!worldObj.isRemote)
		{
			if(inventory[0] != null && gasTank.getGas() != null)
			{
					gasTank.draw(GasTransmission.addGas(inventory[0], gasTank.getGas()), true);
			}

			if(inventory[1] != null && (gasTank.getGas() == null || gasTank.getGas().amount < gasTank.getMaxGas()))
			{
				gasTank.receive(GasTransmission.removeGas(inventory[1], gasTank.getGasType(), gasTank.getNeeded()), true);
			}

			if(gasTank.getGas() != null && MekanismUtils.canFunction(this) && dumping != GasMode.DUMPING)
			{
				if(configComponent.isEjecting(TransmissionType.GAS))
				{
					GasStack toSend = new GasStack(gasTank.getGas().getGas(), Math.min(gasTank.getStored(), output));
					gasTank.draw(GasTransmission.emit(toSend, this, configComponent.getSidesForData(TransmissionType.GAS, facing, 2)), true);
				}
			}

			if(dumping == GasMode.DUMPING)
			{
				gasTank.draw(8, true);
			}
	
			if(dumping == GasMode.DUMPING_EXCESS && gasTank.getNeeded() < output)
			{
				gasTank.draw(output-gasTank.getNeeded(), true);
			}
			
			int newGasAmount = gasTank.getStored();
			
			if(newGasAmount != currentGasAmount)
			{
				MekanismUtils.saveChunk(this);
			}
			
			currentGasAmount = newGasAmount;
		}
	}

	@Override
	public boolean canExtractItem(int slotID, ItemStack itemstack, EnumFacing side)
	{
		if(slotID == 1)
		{
			return (itemstack.getItem() instanceof IGasItem && ((IGasItem)itemstack.getItem()).getGas(itemstack) == null);
		}
		else if(slotID == 0)
		{
			return (itemstack.getItem() instanceof IGasItem && ((IGasItem)itemstack.getItem()).getGas(itemstack) != null &&
			    ((IGasItem)itemstack.getItem()).getGas(itemstack).amount == ((IGasItem)itemstack.getItem()).getMaxGas(itemstack));
		}

		return false;
	}

	@Override
	public boolean isItemValidForSlot(int slotID, ItemStack itemstack)
	{
		if(slotID == 0)
		{
			return itemstack.getItem() instanceof IGasItem && (gasTank.getGas() == null || ((IGasItem)itemstack.getItem()).canReceiveGas(itemstack, gasTank.getGas().getGas()));
		}
		else if(slotID == 1)
		{
			return itemstack.getItem() instanceof IGasItem && (gasTank.getGas() == null || ((IGasItem)itemstack.getItem()).canProvideGas(itemstack, gasTank.getGas().getGas()));
		}

		return false;
	}

	@Override
	public int[] getSlotsForFace(EnumFacing side)
	{
		return configComponent.getOutput(TransmissionType.ITEM, side, facing).availableSlots;
	}

	@Override
	public int receiveGas(EnumFacing side, GasStack stack, boolean doTransfer)
	{
		return gasTank.receive(stack, doTransfer);
	}

	@Override
	public int receiveGas(EnumFacing side, GasStack stack)
	{
		return receiveGas(side, stack, true);
	}

	@Override
	public GasStack drawGas(EnumFacing side, int amount, boolean doTransfer)
	{
		return null;
	}

	@Override
	public GasStack drawGas(EnumFacing side, int amount)
	{
		return drawGas(side, amount, true);
	}

	@Override
	public boolean canDrawGas(EnumFacing side, Gas type)
	{
		return gasTank.canDraw(type);
	}

	@Override
	public boolean canReceiveGas(EnumFacing side, Gas type)
	{
		if(configComponent.getSidesForData(TransmissionType.GAS, facing, 1).contains(side))
		{
			return gasTank.canReceive(type);
		}

		return false;
	}

	@Override
	public void handlePacketData(ByteBuf dataStream)
	{
		if(!worldObj.isRemote)
		{
			int type = dataStream.readInt();

			if(type == 0)
			{
				int index = (dumping.ordinal() + 1)%GasMode.values().length;
				dumping = GasMode.values()[index];
			}

			for(EntityPlayer player : playersUsing)
			{
				Mekanism.packetHandler.sendTo(new TileEntityMessage(Coord4D.get(this), getNetworkedData(new ArrayList<Object>())), (EntityPlayerMP)player);
			}

			return;
		}

		super.handlePacketData(dataStream);

		if(dataStream.readBoolean())
		{
			gasTank.setGas(new GasStack(GasRegistry.getGas(dataStream.readInt()), dataStream.readInt()));
		}
		else {
			gasTank.setGas(null);
		}

		dumping = GasMode.values()[dataStream.readInt()];
		controlType = RedstoneControl.values()[dataStream.readInt()];

		MekanismUtils.updateBlock(worldObj, getPos());
	}

	@Override
	public void readFromNBT(NBTTagCompound nbtTags)
	{
		super.readFromNBT(nbtTags);

		gasTank.read(nbtTags.getCompoundTag("gasTank"));
		dumping = GasMode.values()[nbtTags.getInteger("dumping")];
		
		controlType = RedstoneControl.values()[nbtTags.getInteger("controlType")];
	}

	@Override
	public void writeToNBT(NBTTagCompound nbtTags)
	{
		super.writeToNBT(nbtTags);

		nbtTags.setTag("gasTank", gasTank.write(new NBTTagCompound()));
		nbtTags.setInteger("dumping", dumping.ordinal());
		nbtTags.setInteger("controlType", controlType.ordinal());
	}

	@Override
	public ArrayList getNetworkedData(ArrayList<Object> data)
	{
		super.getNetworkedData(data);

		if(gasTank.getGas() != null)
		{
			data.add(true);
			data.add(gasTank.getGas().getGas().getID());
			data.add(gasTank.getStored());
		}
		else {
			data.add(false);
		}

		data.add(dumping.ordinal());
		data.add(controlType.ordinal());

		return data;
	}

	@Override
	public boolean canSetFacing(int side)
	{
		return side != 0 && side != 1;
	}

	@Override
	public boolean canTubeConnect(EnumFacing side)
	{
		return true;
	}

	public int getRedstoneLevel()
	{
        double fractionFull = (float)gasTank.getStored()/(float)gasTank.getMaxGas();
        return MathHelper.floor_float((float)(fractionFull * 14.0F)) + (fractionFull > 0 ? 1 : 0);
	}
	
	@Override
	public RedstoneControl getControlType()
	{
		return controlType;
	}

	@Override
	public void setControlType(RedstoneControl type)
	{
		controlType = type;
	}

	@Override
	public boolean canPulse()
	{
		return false;
	}

	@Override
	public IEjector getEjector()
	{
		return ejectorComponent;
	}
	
	@Override
	public TileComponentConfig getConfig()
	{
		return configComponent;
	}
	
	@Override
	public EnumFacing getOrientation()
	{
		return facing;
	}
}
