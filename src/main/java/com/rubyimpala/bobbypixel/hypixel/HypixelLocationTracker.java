package com.rubyimpala.bobbypixel.hypixel;

import com.rubyimpala.bobbypixel.BobbyPixel;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.azureaaron.hmapi.events.HypixelPacketEvents;
import net.azureaaron.hmapi.network.HypixelNetworking;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.LocationUpdateS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

public class HypixelLocationTracker {
    private static volatile boolean onHypixel = false;
    private static volatile String currentCacheKey = null;
    private static volatile String pendingSessionId = null;

    public static void init() {
        HypixelPacketEvents.HELLO.register(_ -> onHypixel = true);
        HypixelPacketEvents.LOCATION_UPDATE.register(HypixelLocationTracker::onPacket);

        ClientPlayConnectionEvents.JOIN.register((_, _, client) -> {
            ServerData server = client.getCurrentServer();
            onHypixel = server != null && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
            currentCacheKey = null;
            pendingSessionId = UUID.randomUUID().toString();

            Object2IntOpenHashMap<CustomPacketPayload.Type<HypixelS2CPacket>> events = new Object2IntOpenHashMap<>();
            events.put(LocationUpdateS2CPacket.ID, 1);
            HypixelNetworking.registerToEvents(events);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((_, _) -> {
            onHypixel = false;
            currentCacheKey = null;
            bobbypixel$cleanupPending();
        });
    }

    private static void onPacket(HypixelS2CPacket packet) {
        if (!(packet instanceof LocationUpdateS2CPacket location)) return;

        onHypixel = true;
        currentCacheKey = computeCacheKey(location);
        applyToActiveWorld();
    }

    private static void applyToActiveWorld() {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.level == null) return;
            if (!(client.level.getChunkSource() instanceof ClientChunkCacheExt ext)) return;

            FakeChunkManager manager = ext.bobby_getFakeChunkManager();
            if (manager instanceof FakeChunkManagerLocationAware aware) {
                aware.bobbypixel$applyResolvedLocation();
            }
        });
    }

    public static boolean isOnHypixel() {
        return onHypixel;
    }

    /** Null = on Hypixel but location not resolved yet for this join (race). */
    public static String getCurrentCacheKey() {
        return currentCacheKey;
    }
    /** Stable per-join id, used to isolate loads that happen before location is known. could be changed to work better... */
    public static String getPendingSessionId() {
        return pendingSessionId;
    }

    private static String computeCacheKey(LocationUpdateS2CPacket location) {
        String type = location.serverType().orElse(null);
        if (type == null) return sanitize(location.serverName());

        String typeLower = type.toLowerCase(Locale.ROOT);

        if (typeLower.contains("lobby")) {
            return "lobby/" + sanitize(typeLower.replace("_lobby", ""));
        }
        if (typeLower.contains("skyblock")) {
            return "skyblock/" + sanitize(mapOrMode(location));
        }
        return sanitize(typeLower) + "/" + sanitize(mapOrMode(location));
    }

    private static String mapOrMode(LocationUpdateS2CPacket location) {
        return location.map().orElseGet(() -> location.mode().orElse("unknown"));
    }

    private static String sanitize(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
    }

    private static void bobbypixel$cleanupPending() {
        Path pendingDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve(HypixelCachePaths.BOBBY_FOLDER).resolve(HypixelCachePaths.HYPIXEL_ROOT).resolve(HypixelCachePaths.PENDING_FOLDER);

        if (!Files.exists(pendingDir)) return;

        try (var stream = Files.walk(pendingDir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            BobbyPixel.LOGGER.debug("Skipped deleting {} (likely still in use)", path, e);
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}