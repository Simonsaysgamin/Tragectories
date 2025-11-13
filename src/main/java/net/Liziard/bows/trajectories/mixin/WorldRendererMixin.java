package net.Liziard.bows.trajectories.mixin;

import net.Liziard.bows.trajectories.client.ProjectileTrajectoryRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilderStorage;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
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

    @Inject(method = "render", at = @At("RETURN"))
    private void onRenderEnd(net.minecraft.client.util.ObjectAllocator allocator, net.minecraft.client.render.RenderTickCounter tickCounter, boolean renderBlockOutline, Camera camera, org.joml.Matrix4f posMatrix1, org.joml.Matrix4f posMatrix2, com.mojang.blaze3d.buffers.GpuBufferSlice fogBuffer, org.joml.Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
        if (this.client == null || this.client.world == null || this.client.player == null) return;
        if (camera == null) return;

        MatrixStack matrices = new MatrixStack();
        VertexConsumerProvider.Immediate immediate = this.bufferBuilders.getEntityVertexConsumers();

        ProjectileTrajectoryRenderer.render(matrices, camera, immediate);
        immediate.draw();
    }
}

