package net.Liziard.bows.trajectories.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.Liziard.bows.trajectories.client.ProjectileTrajectoryRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.render.WorldRenderer")
public class WorldRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void onRenderEnd(ObjectAllocator allocator,
                             RenderTickCounter renderTickCounter,
                             boolean someFlag1,
                             Camera camera,
                             Matrix4f matA,
                             Matrix4f matB,
                             Matrix4f matC,
                             GpuBufferSlice gpuBufferSlice,
                             Vector4f vector4f,
                             boolean someFlag2,
                             CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        // ProjectileTrajectoryRenderer.render currently only needs a MatrixStack and Camera;
        // VertexConsumerProvider is unused, so pass null safely.
        MatrixStack ms = new MatrixStack();
        ProjectileTrajectoryRenderer.render(ms, camera, null);
    }
}

