package net.Liziard.bows.trajectories.mixin;

// This mixin is no longer used - we use Fabric API's WorldRenderEvents.LAST instead
// See TrajectoriesClient.java

/*
import net.Liziard.bows.trajectories.client.ProjectileTrajectoryRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @Shadow @Final private MinecraftClient client;
    @Shadow @Final private BufferBuilderStorage bufferBuilders;

    // Inject at return of render method - version-independent
    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(CallbackInfo ci) {
        if (this.client.world == null || this.client.player == null) return;
        Camera camera = this.client.gameRenderer.getCamera();
        if (camera == null) return;

        MatrixStack matrices = new MatrixStack();

        VertexConsumerProvider.Immediate immediate = this.bufferBuilders.getEntityVertexConsumers();

        ProjectileTrajectoryRenderer.render(matrices, camera, immediate);
        immediate.draw();
    }
}
*/

