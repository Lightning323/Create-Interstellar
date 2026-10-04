package com.lightning.northstar.physics;

import com.lightning.northstar.contraption.rocket.RocketDestination;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Persisted flight state attached to the Sable plot that represents a vessel. */
public final class RocketSublevelState {

    private static final String DATA_KEY = "NorthstarRocket";
    private static final Map<ServerSubLevel, RocketShipState> CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RocketSublevelState() {
    }

    public static RocketShipState get(ServerSubLevel subLevel) {
        return CACHE.computeIfAbsent(subLevel, level -> {
            CompoundTag root = level.getUserDataTag();
            RocketShipState state = new RocketShipState();
            if (root != null && root.contains(DATA_KEY, CompoundTag.TAG_COMPOUND)) {
                state.read(root.getCompound(DATA_KEY));
            }
            return state;
        });
    }

    public static RocketShipState initialize(ServerSubLevel subLevel, RocketDestination destination) {
        return initialize(subLevel, destination, null);
    }

    public static RocketShipState initialize(ServerSubLevel subLevel, RocketDestination destination,
                                             RocketDestination origin) {
        RocketShipState state = new RocketShipState();
        state.setDestination(destination);
        state.setOrigin(origin);
        CACHE.put(subLevel, state);
        save(subLevel, state);
        return state;
    }

    public static void save(ServerSubLevel subLevel) {
        save(subLevel, get(subLevel));
    }

    public static void save(ServerSubLevel subLevel, RocketShipState state) {
        CompoundTag root = subLevel.getUserDataTag();
        if (root == null) root = new CompoundTag();
        CompoundTag flight = new CompoundTag();
        state.write(flight);
        root.put(DATA_KEY, flight);
        subLevel.setUserDataTag(root);
    }

    public static boolean isRocket(ServerSubLevel subLevel) {
        CompoundTag root = subLevel.getUserDataTag();
        return root != null && root.contains(DATA_KEY, CompoundTag.TAG_COMPOUND);
    }

    public static void forget(ServerSubLevel subLevel) {
        CACHE.remove(subLevel);
    }
}
