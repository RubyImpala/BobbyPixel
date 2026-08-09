package com.rubyimpala.bobbypixel.hypixel;

import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.azureaaron.hmapi.events.HypixelPacketEvents;
import net.azureaaron.hmapi.network.HypixelNetworking;
import net.azureaaron.hmapi.network.packet.s2c.HypixelS2CPacket;
import net.azureaaron.hmapi.network.packet.v1.s2c.LocationUpdateS2CPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class HypixelLocationTracker {
    private static volatile boolean onHypixel = false;
    private static final AtomicReference<String> currentCacheKey = new AtomicReference<>(null);
    private static volatile String pendingSessionId = null;

    public static void init() {
        HypixelPacketEvents.HELLO.register(packet -> onHypixel = true);
        HypixelPacketEvents.LOCATION_UPDATE.register(HypixelLocationTracker::onPacket);

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ServerData server = client.getCurrentServer();
            onHypixel = server != null && server.ip != null
                    && server.ip.toLowerCase(Locale.ROOT).contains("hypixel.net");
            currentCacheKey.set(null);
            pendingSessionId = UUID.randomUUID().toString();

            // Must (re)subscribe on every connection — this doesn't persist across
            // reconnects/server transfers, which is what was breaking location updates
            // after the first join.
            HypixelNetworking.registerToEvents(Util.make(new Object2IntOpenHashMap<>(), map -> {
                map.put(LocationUpdateS2CPacket.ID, 1);
            }));
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            onHypixel = false;
            currentCacheKey.set(null);
            bobbypixel$cleanupPending();
        });
    }

    private static void onPacket(HypixelS2CPacket packet) {
        if (packet instanceof LocationUpdateS2CPacket location) {
            onHypixel = true;
            currentCacheKey.set(computeCacheKey(location));

            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                if (client.level == null) return;
                Object chunkSource = client.level.getChunkSource();
                if (chunkSource instanceof ClientChunkCacheExt ext) {
                    FakeChunkManager manager = ext.bobby_getFakeChunkManager();
                    if (manager instanceof FakeChunkManagerLocationAware aware) {
                        aware.bobbypixel$applyResolvedLocation();
                    }
                }
            });
        }
    }

    /** True as soon as we know we're on a Hypixel-mod-api-enabled server, even before location is known. */
    public static boolean isOnHypixel() {
        return onHypixel;
    }

    /** Null = on Hypixel but location not resolved yet for this join (race). */
    public static String getCurrentCacheKey() {
        return currentCacheKey.get();
    }

    /** Stable per-join id, used to isolate loads that happen before location is known. */
    public static String getPendingSessionId() {
        return pendingSessionId;
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

    private static void bobbypixel$cleanupPending() {
        Path pendingDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve(".bobby").resolve("hypixel").resolve("_pending");

        if (!Files.exists(pendingDir)) return;

        try (var stream = Files.walk(pendingDir)) {
            stream.sorted(Comparator.reverseOrder()) // delete children before parents
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort — a file might still be closing from a save thread
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}