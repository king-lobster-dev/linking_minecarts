package net.lobster.linking_minecarts.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CartLinkProvider implements ICapabilitySerializable<CompoundTag> {

    private final CartLinkData data = new CartLinkData();
    private final LazyOptional<CartLinkData> opt = LazyOptional.of(() -> data);

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap,
                                                      @Nullable Direction side) {
        return cap == ModCapabilities.CART_LINK ? opt.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        if (data.getLeader()   != null) tag.putUUID("Leader",   data.getLeader());
        if (data.getFollower() != null) tag.putUUID("Follower", data.getFollower());
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        if (tag.hasUUID("Leader"))   data.setLeader(tag.getUUID("Leader"));
        if (tag.hasUUID("Follower")) data.setFollower(tag.getUUID("Follower"));
    }
}