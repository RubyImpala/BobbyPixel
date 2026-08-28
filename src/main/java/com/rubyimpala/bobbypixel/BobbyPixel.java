package com.rubyimpala.bobbypixel;

import com.rubyimpala.bobbypixel.hypixel.HypixelLocationTracker;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BobbyPixel implements ModInitializer {
	public static final String MOD_ID = "bobbypixel";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		HypixelLocationTracker.init();
		LOGGER.info("BobbyPixel initialized");
	}
}