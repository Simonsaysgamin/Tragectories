package net.Liziard.bows.trajectories.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;


public class TrajectoriesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register world render event - renders after everything else
        WorldRenderEvents.LAST.register(context -> {
            ProjectileTrajectoryRenderer.render(
                context.matrixStack(),
                context.camera(),
                context.consumers()
            );
        });
    }
}
