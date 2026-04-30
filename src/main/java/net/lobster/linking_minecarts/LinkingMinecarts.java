package net.lobster.linking_minecarts;

import net.lobster.linking_minecarts.capability.CartLinkProvider;
import net.lobster.linking_minecarts.event.CartTickHandler;
import net.lobster.linking_minecarts.event.InteractionHandler;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;

import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(LinkingMinecarts.MOD_ID)
public class LinkingMinecarts {

    public static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "linking_minecarts";

    public LinkingMinecarts() {
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new InteractionHandler());
        MinecraftForge.EVENT_BUS.register(new CartTickHandler());
    }

    @SuppressWarnings("removal")
    @SubscribeEvent
    public void attachCaps(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof AbstractMinecart) {
            event.addCapability(
                    new ResourceLocation(MOD_ID, "cart_links"),
                    new CartLinkProvider()
            );
        }
    }
}