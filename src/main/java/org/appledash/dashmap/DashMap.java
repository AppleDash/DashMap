package org.appledash.dashmap;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkConstants;

@Mod("dashmap")
public class DashMap {
    public static DashMap instance;
    private final MapManager mapManager = new MapManager();
    private final MapRenderer renderer = new MapRenderer();

    public DashMap() {
        DashMap.instance = this;

        /* Ensure that the client doesn't think this mod is required on servers it wants to join */
        ModLoadingContext.get().registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (a, b) -> true
                )
        );

        /* Only register our events on the client side */
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> DashMapEventHandler::register);
    }

    public MapManager getMapManager() {
        return this.mapManager;
    }

    public MapRenderer getMapRenderer() {
        return this.renderer;
    }
}
