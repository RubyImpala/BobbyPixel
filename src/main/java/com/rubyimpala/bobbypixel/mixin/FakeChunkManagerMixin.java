package com.rubyimpala.bobbypixel.mixin;

import com.rubyimpala.bobbypixel.hypixel.HypixelLocationTracker;
import de.johni0702.minecraft.bobby.BobbyConfig;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.util.FileSystemUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.nio.file.Path;

@Mixin(FakeChunkManager.class)
public abstract class FakeChunkManagerMixin {

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lde/johni0702/minecraft/bobby/Worlds;getFor(Ljava/nio/file/Path;)Lde/johni0702/minecraft/bobby/Worlds;"
            )
    )
    private Path bobbypixel$redirectWorldsPath(Path storagePath) {
        return bobbypixel$redirect(storagePath);
    }

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lde/johni0702/minecraft/bobby/FakeChunkStorage;getFor(Ljava/nio/file/Path;Z)Lde/johni0702/minecraft/bobby/FakeChunkStorage;",
                    ordinal = 0 // first occurrence only — leaves the later bobby-fallback call (ordinal 1) untouched
            ),
            index = 0
    )
    private Path bobbypixel$redirectStoragePath(Path storagePath) {
        return bobbypixel$redirect(storagePath);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lde/johni0702/minecraft/bobby/BobbyConfig;isDynamicMultiWorld()Z"
            )
    )
    private boolean bobbypixel$forceStaticStorageOnHypixel(BobbyConfig config) {
        if (HypixelLocationTracker.getCurrentCacheKey() != null) {
            // We already know the exact instance from Hypixel's location data, so skip
            // Bobby's per-join "new world" separation and just use one persistent cache.
            return false;
        }
        return config.isDynamicMultiWorld();
    }

    private Path bobbypixel$redirect(Path storagePath) {
        String cacheKey = HypixelLocationTracker.getCurrentCacheKey();
        if (cacheKey == null) {
            return storagePath; // not on Hypixel — leave Bobby's default path alone
        }

        Path result = Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby").resolve("hypixel");
        for (String segment : cacheKey.split("/")) {
            result = FileSystemUtils.resolveSafeDirectoryName(result, segment);
        }
        return result;
    }
}