package net.Liziard.bows.trajectories.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;


public class TrajectoriesClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Register client tick end event
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Trajectory rendering happens via direct rendering in tick
            if (client.world != null && client.player != null) {
                // Rendering is triggered automatically each frame
            }
        });
    }
}
