package com.rubyimpala.bobbypixel.mixin;

import com.rubyimpala.bobbypixel.hypixel.FakeChunkManagerLocationAware;
import com.rubyimpala.bobbypixel.hypixel.HypixelLocationTracker;
import de.johni0702.minecraft.bobby.BobbyConfig;
import de.johni0702.minecraft.bobby.FakeChunkManager;
import de.johni0702.minecraft.bobby.FakeChunkStorage;
import de.johni0702.minecraft.bobby.util.FileSystemUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Mixin(FakeChunkManager.class)
public abstract class FakeChunkManagerMixin implements FakeChunkManagerLocationAware {

    @Mutable
    @Shadow
    @Final
    private FakeChunkStorage storage;

    @Shadow
    @Final
    private List<Function<ChunkPos, CompletableFuture<Optional<CompoundTag>>>> storages;

    @Inject(method = "getCurrentWorldOrServerName", at = @At("RETURN"), cancellable = true)
    private static void bobbypixel$overrideServerName(ClientPacketListener networkHandler, CallbackInfoReturnable<String> cir) {
        if (HypixelLocationTracker.isOnHypixel()) {
            cir.setReturnValue("hypixel");
        }
    }

    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lde/johni0702/minecraft/bobby/BobbyConfig;isDynamicMultiWorld()Z"))
    private boolean bobbypixel$forceStaticStorageOnHypixel(BobbyConfig config) {
        if (HypixelLocationTracker.isOnHypixel()) {
            return false;
        }
        return config.isDynamicMultiWorld();
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lde/johni0702/minecraft/bobby/Worlds;getFor(Ljava/nio/file/Path;)Lde/johni0702/minecraft/bobby/Worlds;"))
    private Path bobbypixel$redirectWorldsPath(Path storagePath) {
        return bobbypixel$computeStoragePath(storagePath);
    }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lde/johni0702/minecraft/bobby/FakeChunkStorage;getFor(Ljava/nio/file/Path;Z)Lde/johni0702/minecraft/bobby/FakeChunkStorage;",
            ordinal = 0), index = 0)
    private Path bobbypixel$redirectStoragePath(Path storagePath) {
        return bobbypixel$computeStoragePath(storagePath);
    }

    @Unique
    private Path bobbypixel$computeStoragePath(Path storagePath) {
        if (!HypixelLocationTracker.isOnHypixel()) {
            return storagePath;
        }

        Path hypixelRoot = Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby").resolve("hypixel");
        String cacheKey = HypixelLocationTracker.getCurrentCacheKey();

        if (cacheKey == null) {
            return FileSystemUtils.resolveSafeDirectoryName(
                    hypixelRoot.resolve("_pending"), HypixelLocationTracker.getPendingSessionId());
        }
        return bobbypixel$resolveKeyPath(hypixelRoot, cacheKey);
    }

    @Unique
    private static Path bobbypixel$resolveKeyPath(Path hypixelRoot, String cacheKey) {
        Path result = hypixelRoot;
        for (String segment : cacheKey.split("/")) {
            result = FileSystemUtils.resolveSafeDirectoryName(result, segment);
        }
        return result;
    }

    // Called from HypixelLocationTracker whenever a location packet resolves — swaps
    // the live storage out from under an already-constructed FakeChunkManager, since
    // by the time we know the location, the constructor has usually already run.
    @Override
    @Unique
    public void bobbypixel$applyResolvedLocation() {
        if (!HypixelLocationTracker.isOnHypixel()) return;
        String cacheKey = HypixelLocationTracker.getCurrentCacheKey();
        if (cacheKey == null) return;

        Path hypixelRoot = Minecraft.getInstance().gameDirectory.toPath().resolve(".bobby").resolve("hypixel");
        Path resolved = bobbypixel$resolveKeyPath(hypixelRoot, cacheKey);

        FakeChunkStorage newStorage = FakeChunkStorage.getFor(resolved, true);
        if (newStorage == this.storage) return; // already correct

        this.storage = newStorage;
        if (!this.storages.isEmpty()) {
            this.storages.set(0, newStorage::loadTag);
        }
    }
}