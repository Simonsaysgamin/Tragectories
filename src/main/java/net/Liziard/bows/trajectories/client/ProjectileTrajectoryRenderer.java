package net.Liziard.bows.trajectories.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders predicted projectile trajectory client-side only.
 */
public class ProjectileTrajectoryRenderer {
    private static final int MAX_STEPS = 0; // 0 = infinite
    private static final int SUBSTEPS_PER_TICK = 4; // increases smoothness/accuracy
    private static final int HARD_POINT_LIMIT = 20000; // safety cap to avoid runaway sim
    private static final double MULTISHOT_OFFSET_RAD = Math.toRadians(15); // crossbow multishot offset (wider for visibility)

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
                item instanceof ExperienceBottleItem ||
                item instanceof net.minecraft.item.ThrowablePotionItem;
        if (!isProjectileItem) return;

        boolean using = player.isUsingItem();
        if (item instanceof BowItem && !using) return;
        if (item instanceof CrossbowItem && !CrossbowItem.isCharged(stack)) return;
        if (item instanceof TridentItem && !using) return;

        Vec3d cameraPos = camera.getPos();

        // Simulate one or multiple paths (multishot)
        List<PathResult> results = new ArrayList<>();
        // Check for multishot enchantment - simpler approach without registry lookup
        boolean hasMultishot = item instanceof CrossbowItem &&
                stack.getEnchantments().getEnchantments().stream()
                        .anyMatch(e -> e.matchesKey(Enchantments.MULTISHOT));

        if (hasMultishot) {
            results.add(simulatePath(player, stack, item, 0.0));
            results.add(simulatePath(player, stack, item, +MULTISHOT_OFFSET_RAD));
            results.add(simulatePath(player, stack, item, -MULTISHOT_OFFSET_RAD));
        } else {
            results.add(simulatePath(player, stack, item, 0.0));
        }

        // Render paths
        for (PathResult res : results) {
            renderTrajectory(matrices, cameraPos, vertexConsumers, res.points, res.finalHit, res.hitEntity);
        }
    }

    private record PathResult(List<Vec3d> points, HitResult finalHit, Entity hitEntity) {}

    private static PathResult simulatePath(PlayerEntity player, ItemStack stack, Item item, double yawOffsetRad) {
        // Vanilla-like initial spawn position offset near bow/crossbow
        float yaw = player.getYaw();
        float yawRad = (float) Math.toRadians(yaw);
        double spawnDx = -MathHelper.cos(yawRad) * 0.16;
        double spawnDz = -MathHelper.sin(yawRad) * 0.16;
        Vec3d start = new Vec3d(player.getX() + spawnDx, player.getEyeY() - 0.1, player.getZ() + spawnDz);

        Vec3d velocity = getInitialVelocity(player, stack, item, yawOffsetRad);
        if (velocity.lengthSquared() < 1e-10) return new PathResult(List.of(), null, null);

        MinecraftClient client = MinecraftClient.getInstance();
        List<Vec3d> points = new ArrayList<>();
        Vec3d pos = start;
        Vec3d vel = velocity;

        HitResult finalHit = null;
        Entity hitEntity = null;

        int pointsAdded = 0;
        outer:
        for (int step = 0; step < (MAX_STEPS > 0 ? MAX_STEPS : Integer.MAX_VALUE); step++) {
            for (int s = 0; s < SUBSTEPS_PER_TICK; s++) {
                points.add(pos);
                if (++pointsAdded >= HARD_POINT_LIMIT) break outer;

                Vec3d nextPos = pos.add(vel.multiply(1.0 / SUBSTEPS_PER_TICK));

                HitResult blockHit = client.world.raycast(new RaycastContext(
                        pos,
                        nextPos,
                        RaycastContext.ShapeType.COLLIDER,
                        RaycastContext.FluidHandling.NONE,
                        player
                ));

                EntityHitResult entityHit = checkEntityCollision(client, player, pos, nextPos);

                HitResult closestHit = null;
                if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK) closestHit = blockHit;
                if (entityHit != null) {
                    double blockDist = blockHit != null && blockHit.getType() == HitResult.Type.BLOCK ?
                            pos.squaredDistanceTo(blockHit.getPos()) : Double.MAX_VALUE;
                    double entityDist = pos.squaredDistanceTo(entityHit.getPos());
                    if (entityDist < blockDist) {
                        closestHit = entityHit;
                        hitEntity = entityHit.getEntity();
                    }
                }

                if (closestHit != null) {
                    points.add(closestHit.getPos());
                    finalHit = closestHit;
                    break outer;
                }

                double dt = 1.0 / SUBSTEPS_PER_TICK;
                boolean inWater = isSegmentInWater(client, pos, nextPos);
                vel = applyPhysics(vel, item, dt, inWater);
                pos = nextPos;

                if (vel.lengthSquared() < 1e-8) break outer;
            }
        }

        return new PathResult(points, finalHit, hitEntity);
    }

    private static boolean isSegmentInWater(MinecraftClient client, Vec3d a, Vec3d b) {
        // sample 3 points along the segment
        for (int i = 0; i <= 2; i++) {
            double t = i / 2.0;
            Vec3d p = a.lerp(b, t);
            BlockPos bp = BlockPos.ofFloored(p);
            if (client.world.getFluidState(bp).isIn(FluidTags.WATER)) return true;
        }
        return false;
    }

    private static Vec3d applyPhysics(Vec3d vel, Item item, double dt, boolean inWater) {
        double g = getGravity(item) * dt;
        Vec3d v = vel.add(0, -g, 0);
        double base = getDrag(item);
        double medium = inWater ? 0.6 : 1.0; // strong slowdown in water for arrows/throwables
        double drag = Math.pow(base * medium, dt);
        return v.multiply(drag);
    }

    private static Vec3d getInitialVelocity(PlayerEntity player, ItemStack stack, Item item, double yawOffsetRad) {
        float pitch = player.getPitch();
        float yaw = player.getYaw();
        float yawRad = (float) (Math.toRadians(yaw) + yawOffsetRad);
        float pitchRad = (float) Math.toRadians(pitch);

        float f = -MathHelper.sin(yawRad) * MathHelper.cos(pitchRad);
        float g = -MathHelper.sin(pitchRad);
        float h = MathHelper.cos(yawRad) * MathHelper.cos(pitchRad);
        Vec3d direction = new Vec3d(f, g, h).normalize();

        float speed;
        if (item instanceof BowItem) {
            int useTicks = player.getItemUseTime();
            float charge = BowItem.getPullProgress(useTicks);
            speed = charge * 3.0F;
        } else if (item instanceof CrossbowItem) {
            speed = 3.15F;
        } else if (item instanceof TridentItem) {
            int useTicks = player.getItemUseTime();
            float charge = Math.min((float) useTicks / 10.0F, 1.0F);
            speed = 2.5F * charge;
        } else if (item instanceof SnowballItem || item instanceof EggItem || item instanceof EnderPearlItem) {
            speed = 1.5F;
        } else if (item instanceof net.minecraft.item.ThrowablePotionItem || item instanceof ExperienceBottleItem) {
            speed = 0.75F;
        } else {
            return Vec3d.ZERO;
        }
        return direction.multiply(speed);
    }

    private static double getGravity(Item item) {
        if (item instanceof BowItem || item instanceof TridentItem || item instanceof CrossbowItem) {
            return 0.05;
        }
        return 0.03; // thrown items
    }

    private static double getDrag(Item item) {
        if (item instanceof BowItem || item instanceof CrossbowItem) {
            return 0.99; // arrow drag
        }
        if (item instanceof TridentItem) {
            return 0.98;
        }
        return 0.99; // thrown items
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

    private static void renderTrajectory(MatrixStack matrices, Vec3d cameraPos,
                                         VertexConsumerProvider vertexConsumers, List<Vec3d> points,
                                         HitResult finalHit, Entity hitEntity) {
        VertexConsumer lineConsumer = vertexConsumers.getBuffer(RenderLayer.getLines());
        MatrixStack.Entry entry = matrices.peek();

        // Render trajectory lines
        Vec3d lastPoint = null;
        for (Vec3d point : points) {
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
                Direction side = Direction.UP;
                if (finalHit instanceof BlockHitResult bhr) {
                    side = bhr.getSide();
                }
                drawImpactQuad(entry, lineConsumer,
                        hitPos.x - cameraPos.x, hitPos.y - cameraPos.y, hitPos.z - cameraPos.z,
                        side);
            } else if (hitEntity != null) {
                drawEntityHitbox(matrices, vertexConsumers, hitEntity, cameraPos.x, cameraPos.y, cameraPos.z);
            }
        }
    }

    private static void drawLine(MatrixStack.Entry entry, VertexConsumer consumer,
                                 double x1, double y1, double z1,
                                 double x2, double y2, double z2,
                                 float r, float g, float b, float a) {
        consumer.vertex(entry.getPositionMatrix(), (float) x1, (float) y1, (float) z1)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f);
        consumer.vertex(entry.getPositionMatrix(), (float) x2, (float) y2, (float) z2)
                .color(r, g, b, a)
                .normal(entry, 0f, 1f, 0f);
    }

    // Oriented impact quad depending on face hit
    private static void drawImpactQuad(MatrixStack.Entry entry, VertexConsumer consumer,
                                       double x, double y, double z, Direction face) {
        float size = 0.25f; // a bit larger for visibility
        float r = 1.0f, g = 0.0f, b = 0.0f, a = 1.0f;
        double eps = 0.01; // push slightly off the surface to prevent z-fighting

        switch (face) {
            case UP:
            case DOWN: {
                // Horizontal square in XZ plane at constant Y
                double cy = y + (face == Direction.UP ? eps : -eps);
                drawLine(entry, consumer, x - size, cy, z - size, x + size, cy, z - size, r, g, b, a);
                drawLine(entry, consumer, x + size, cy, z - size, x + size, cy, z + size, r, g, b, a);
                drawLine(entry, consumer, x + size, cy, z + size, x - size, cy, z + size, r, g, b, a);
                drawLine(entry, consumer, x - size, cy, z + size, x - size, cy, z - size, r, g, b, a);
                break;
            }
            case NORTH: // -Z
            case SOUTH: { // +Z
                // Vertical square in XY plane at constant Z
                double cz = z + (face == Direction.SOUTH ? eps : -eps); // constant z offset along normal
                drawLine(entry, consumer, x - size, y - size, cz, x + size, y - size, cz, r, g, b, a);
                drawLine(entry, consumer, x + size, y - size, cz, x + size, y + size, cz, r, g, b, a);
                drawLine(entry, consumer, x + size, y + size, cz, x - size, y + size, cz, r, g, b, a);
                drawLine(entry, consumer, x - size, y + size, cz, x - size, y - size, cz, r, g, b, a);
                break;
            }
            case EAST: // +X
            case WEST: { // -X
                // Vertical square in YZ plane at constant X
                double cx = x + (face == Direction.EAST ? eps : -eps); // constant x offset along normal
                drawLine(entry, consumer, cx, y - size, z - size, cx, y - size, z + size, r, g, b, a);
                drawLine(entry, consumer, cx, y - size, z + size, cx, y + size, z + size, r, g, b, a);
                drawLine(entry, consumer, cx, y + size, z + size, cx, y + size, z - size, r, g, b, a);
                drawLine(entry, consumer, cx, y + size, z - size, cx, y - size, z - size, r, g, b, a);
                break;
            }
        }
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
