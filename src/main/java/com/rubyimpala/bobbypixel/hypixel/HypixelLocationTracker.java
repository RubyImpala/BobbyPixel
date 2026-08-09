package com.rubyimpala.bobbypixel.hypixel;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.azureaaron.hmapi.events.HypixelPacketEvents;
import net.azureaaron.hmapi.network.HypixelNetworking;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.LocationUpdateS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.util.Util;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class HypixelLocationTracker {
    private static final AtomicReference<String> currentCacheKey = new AtomicReference<>(null);

    public static void init() {
        HypixelPacketEvents.LOCATION_UPDATE.register(HypixelLocationTracker::onPacket);

        // Required once: tells HM API to ask Hypixel to actually send us location updates.
        HypixelNetworking.registerToEvents(Util.make(new Object2IntOpenHashMap<>(), map -> {
            map.put(LocationUpdateS2CPacket.ID, 1);
        }));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> currentCacheKey.set(null));
    }

    private static void onPacket(HypixelS2CPacket packet) {
        if (packet instanceof LocationUpdateS2CPacket location) {
            currentCacheKey.set(computeCacheKey(location));
        }
    }

    public static String getCurrentCacheKey() {
        return currentCacheKey.get();
    }

    private static String computeCacheKey(LocationUpdateS2CPacket location) {
        String type = location.serverType().orElse(null);
        if (type == null) {
            return sanitize(location.serverName());
        }

        String typeLower = type.toLowerCase(Locale.ROOT);

        if (typeLower.contains("lobby")) {
            return "lobby/" + sanitize(typeLower.replace("_lobby", ""));
        }
        if (typeLower.contains("skyblock")) {
            String island = location.map().orElseGet(() -> location.mode().orElse("unknown"));
            return "skyblock/" + sanitize(island);
        }

        String map = location.map().orElseGet(() -> location.mode().orElse("unknown"));
        return sanitize(typeLower) + "/" + sanitize(map);
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
    }
}