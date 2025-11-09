package net.Liziard.bows.trajectories.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

public final class ProjectileTrajectoryRenderer {
    private static final int MAX_STEPS = 500;
    private static final List<Vector3d> points = new ArrayList<>();

    private ProjectileTrajectoryRenderer() {}

    public static void render(MatrixStack matrices, Camera camera, VertexConsumerProvider vertexConsumers) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || client.player == null) return;

        PlayerEntity player = client.player;
        ItemStack stack = player.getMainHandStack();
        Item item = stack.getItem();

        boolean isProjectileItem = item instanceof BowItem ||
                item instanceof CrossbowItem ||
                item instanceof TridentItem ||
                item instanceof SnowballItem ||
                item instanceof EggItem ||
                item instanceof EnderPearlItem ||
                item instanceof PotionItem;

        if (!isProjectileItem) {
            stack = player.getOffHandStack();
            item = stack.getItem();
            isProjectileItem = item instanceof BowItem ||
                    item instanceof CrossbowItem ||
                    item instanceof TridentItem ||
                    item instanceof SnowballItem ||
                    item instanceof EggItem ||
                    item instanceof EnderPearlItem ||
                    item instanceof PotionItem;
        }

        if (!isProjectileItem) return;

        if (item instanceof BowItem && !player.isUsingItem()) return;
        if (item instanceof CrossbowItem && !CrossbowItem.isCharged(stack)) return;

        simulateAndRender(player, item, matrices, camera, vertexConsumers);
    }

    private static void simulateAndRender(PlayerEntity player, Item item,
                                          MatrixStack matrices, Camera camera,
                                          VertexConsumerProvider vertexConsumers) {
        Vec3d cameraPos = camera.getPos();

        // Clear previous points
        points.clear();

        // Calculate initial position (from player's eye)
        Vec3d pos = new Vec3d(player.getX(), player.getEyeY(), player.getZ());

        // Get initial velocity
        Vec3d vel = getInitialVelocity(player, item, player.getYaw(), player.getPitch());
        if (vel.lengthSquared() < 1e-6) return;

        // Add starting point
        points.add(new Vector3d(pos.x, pos.y, pos.z));

        // Simulate trajectory
        HitResult finalHit = null;
        Entity hitEntity = null;

        for (int i = 0; i < MAX_STEPS; i++) {
            Vec3d prevPos = pos;

            // Apply gravity
            vel = vel.add(0, -getGravity(item), 0);
            // Apply drag
            vel = vel.multiply(getDrag());
            // Update position
            pos = pos.add(vel);

            // Check for collisions
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.world == null) break;

            // Block collision
            HitResult blockHit = client.world.raycast(new RaycastContext(
                prevPos, pos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
            ));

            // Entity collision
            EntityHitResult entityHit = checkEntityCollision(client, player, prevPos, pos);

            // Determine closest hit
            HitResult closestHit = null;
            if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) {
                closestHit = blockHit;
            }
            if (entityHit != null) {
                if (closestHit == null || prevPos.squaredDistanceTo(entityHit.getPos()) < prevPos.squaredDistanceTo(closestHit.getPos())) {
                    closestHit = entityHit;
                    hitEntity = entityHit.getEntity();
                }
            }

            if (closestHit != null) {
                Vec3d hitPos = closestHit.getPos();
                points.add(new Vector3d(hitPos.x, hitPos.y, hitPos.z));
                finalHit = closestHit;
                break;
            }

            // Add point
            points.add(new Vector3d(pos.x, pos.y, pos.z));

            // Stop if velocity is too low
            if (vel.lengthSquared() < 0.0001) break;
        }

        // Render the trajectory
        renderTrajectory(matrices, cameraPos, vertexConsumers, finalHit, hitEntity);
    }

    private static Vec3d getInitialVelocity(PlayerEntity player, Item item, float yaw, float pitch) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);

        float f = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad);
        float g = -MathHelper.sin(pitchRad);
        float h = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad);

        Vec3d direction = new Vec3d(f, g, h).normalize();
        double speed;

        if (item instanceof BowItem) {
            int useTicks = player.getItemUseTime();
            float charge = BowItem.getPullProgress(useTicks);
            if (charge < 0.1f) return Vec3d.ZERO;
            speed = charge * 3.0;
        } else if (item instanceof CrossbowItem) {
            speed = 3.15;
        } else if (item instanceof TridentItem) {
            speed = 2.5;
        } else if (item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderPearlItem) {
            speed = 1.5;
        } else if (item instanceof PotionItem) {
            speed = 0.5;
        } else {
            return Vec3d.ZERO;
        }

        return direction.multiply(speed);
    }

    private static double getGravity(Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem || item instanceof PotionItem) {
            return 0.05;
        }
        return 0.03;
    }

    private static double getDrag() {
        return 0.99;
    }

    private static void renderTrajectory(MatrixStack matrices, Vec3d cameraPos,
                                        VertexConsumerProvider vertexConsumers,
                                        HitResult finalHit, Entity hitEntity) {
        if (points.isEmpty()) return;

        VertexConsumer lineConsumer = vertexConsumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();

        // Render trajectory lines
        Vector3d lastPoint = null;
        for (Vector3d point : points) {
            if (lastPoint != null) {
                drawLine(entry, lineConsumer,
                    lastPoint.x - cameraPos.x, lastPoint.y - cameraPos.y, lastPoint.z - cameraPos.z,
                    point.x - cameraPos.x, point.y - cameraPos.y, point.z - cameraPos.z,
                    1.0f, 1.0f, 1.0f, 0.8f);
            }
            lastPoint = point;
        }

        // Render hit indicator
        if (finalHit != null) {
            if (finalHit.getType() == HitResult.Type.BLOCK) {
                Vec3d hitPos = finalHit.getPos();
                drawImpactBox(entry, lineConsumer,
                    hitPos.x - cameraPos.x, hitPos.y - cameraPos.y, hitPos.z - cameraPos.z);
            } else if (hitEntity != null) {
                drawEntityHitbox(matrices, vertexConsumers, hitEntity, cameraPos.x, cameraPos.y, cameraPos.z);
            }
        }
    }

    private static void drawLine(MatrixStack.Entry entry, VertexConsumer consumer,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 float r, float g, float b, float a) {
        consumer.vertex(entry.getPositionMatrix(), (float)x1, (float)y1, (float)z1).color(r, g, b, a).normal(entry, 0f, 1f, 0f);
        consumer.vertex(entry.getPositionMatrix(), (float)x2, (float)y2, (float)z2).color(r, g, b, a).normal(entry, 0f, 1f, 0f);
    }

    private static void drawImpactBox(MatrixStack.Entry entry, VertexConsumer consumer,
                                      double x, double y, double z) {
        float size = 0.15f;
        float r = 1.0f, g = 0.0f, b = 0.0f, a = 1.0f;

        // Draw a small box at impact point
        // Bottom face
        drawLine(entry, consumer, x - size, y, z - size, x + size, y, z - size, r, g, b, a);
        drawLine(entry, consumer, x + size, y, z - size, x + size, y, z + size, r, g, b, a);
        drawLine(entry, consumer, x + size, y, z + size, x - size, y, z + size, r, g, b, a);
        drawLine(entry, consumer, x - size, y, z + size, x - size, y, z - size, r, g, b, a);
    }

    private static EntityHitResult checkEntityCollision(MinecraftClient client, PlayerEntity shooter, Vec3d start, Vec3d end) {
        if (client.world == null) return null;

        EntityHitResult closestHit = null;
        double closestDistance = Double.MAX_VALUE;

        Box box = new Box(start, end).expand(1.0);

        for (Entity entity : client.world.getOtherEntities(shooter, box, e -> e.isAttackable() && !e.isSpectator())) {
            Box entityBox = entity.getBoundingBox().expand(0.3);
            java.util.Optional<Vec3d> hit = entityBox.raycast(start, end);
            if (hit.isPresent()) {
                double distance = start.squaredDistanceTo(hit.get());
                if (distance < closestDistance) {
                    closestDistance = distance;
                    closestHit = new EntityHitResult(entity, hit.get());
                }
            }
        }

        return closestHit;
    }

    private static void drawEntityHitbox(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                         Entity entity, double camX, double camY, double camZ) {
        Box box = entity.getBoundingBox();
        double x1 = box.minX - camX;
        double y1 = box.minY - camY;
        double z1 = box.minZ - camZ;
        double x2 = box.maxX - camX;
        double y2 = box.maxY - camY;
        double z2 = box.maxZ - camZ;

        VertexConsumer lineConsumer = vertexConsumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();
        float r = 1.0f, g = 0.0f, b = 0.0f, a = 1.0f;

        // Draw the 12 edges of the bounding box in red
        // Bottom face (4 edges)
        drawLine(entry, lineConsumer, x1, y1, z1, x2, y1, z1, r, g, b, a);
        drawLine(entry, lineConsumer, x2, y1, z1, x2, y1, z2, r, g, b, a);
        drawLine(entry, lineConsumer, x2, y1, z2, x1, y1, z2, r, g, b, a);
        drawLine(entry, lineConsumer, x1, y1, z2, x1, y1, z1, r, g, b, a);

        // Top face (4 edges)
        drawLine(entry, lineConsumer, x1, y2, z1, x2, y2, z1, r, g, b, a);
        drawLine(entry, lineConsumer, x2, y2, z1, x2, y2, z2, r, g, b, a);
        drawLine(entry, lineConsumer, x2, y2, z2, x1, y2, z2, r, g, b, a);
        drawLine(entry, lineConsumer, x1, y2, z2, x1, y2, z1, r, g, b, a);

        // Vertical edges (4 edges)
        drawLine(entry, lineConsumer, x1, y1, z1, x1, y2, z1, r, g, b, a);
        drawLine(entry, lineConsumer, x2, y1, z1, x2, y2, z1, r, g, b, a);
        drawLine(entry, lineConsumer, x2, y1, z2, x2, y2, z2, r, g, b, a);
        drawLine(entry, lineConsumer, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }
}
