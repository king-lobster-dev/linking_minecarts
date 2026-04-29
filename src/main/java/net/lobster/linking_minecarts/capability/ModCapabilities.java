package net.lobster.linking_minecarts.capability;

import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;

public class ModCapabilities {

    public static final Capability<CartLinkData> CART_LINK =
            CapabilityManager.get(new CapabilityToken<>() {});
}