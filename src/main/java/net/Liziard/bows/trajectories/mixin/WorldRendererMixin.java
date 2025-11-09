package net.Liziard.bows.trajectories.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.Liziard.bows.trajectories.client.ProjectileTrajectoryRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
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

    // Inject at TAIL of render with full 1.21.9 signature - renders after world
    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderEnd(ObjectAllocator allocator,
                             RenderTickCounter tickCounter,
                             boolean renderBlockOutline,
                             Camera camera,
                             Matrix4f positionMatrix,
                             Matrix4f matrix4f,
                             Matrix4f projectionMatrix,
                             GpuBufferSlice fogBuffer,
                             Vector4f fogColor,
                             boolean renderSky,
                             CallbackInfo ci) {
        if (this.client.world == null || this.client.player == null) return;
        if (camera == null) return;

        MatrixStack matrices = new MatrixStack();
        // Apply the position matrix for proper world-space rendering
        matrices.multiplyPositionMatrix(positionMatrix);

        VertexConsumerProvider.Immediate immediate = this.bufferBuilders.getEntityVertexConsumers();

        ProjectileTrajectoryRenderer.render(matrices, camera, immediate);
        immediate.draw();
    }
}

