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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.particle.ParticleTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders predicted projectile trajectory client-side only.
 */
public class ProjectileTrajectoryRenderer {
    private static final int SUBSTEPS_PER_TICK = 4; // increases smoothness/accuracy
    private static final int HARD_POINT_LIMIT = 20000; // safety cap to avoid runaway sim
    private static final double MULTISHOT_OFFSET_RAD = Math.toRadians(15); // crossbow multishot offset (wider for visibility)

    // spawn parameters for thin, short-lived dust particles (approximating a single-pixel line)
    private static final int MAX_PARTICLES_PER_FRAME = 40; // much fewer -> looks like a thin line
    private static final double MIN_SPAWN_DISTANCE = 4.0; // avoid spawning very close to camera
    private static final double MAX_SPAWN_DISTANCE = 60.0; // don't spawn beyond this distance

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

        Vec3d cameraPos = new Vec3d(camera.getFocusedEntity().getX(), camera.getFocusedEntity().getY(), camera.getFocusedEntity().getZ()); // Use focused entity's coordinates

        // Simulate one or multiple paths (multishot)
        List<PathResult> results = new ArrayList<>();
        boolean hasMultishot = item instanceof CrossbowItem &&
                stack.getEnchantments().getEnchantments().stream()
                        .anyMatch(e -> e.matchesKey(Enchantments.MULTISHOT));

        if (hasMultishot) {
            results.add(simulatePath(player, item, 0.0));
            results.add(simulatePath(player, item, +MULTISHOT_OFFSET_RAD));
            results.add(simulatePath(player, item, -MULTISHOT_OFFSET_RAD));
        } else {
            results.add(simulatePath(player, item, 0.0));
        }

        // Draw a single-pixel thin line along each simulated path
        for (PathResult res : results) {
            renderLineAlongPath(matrices, client, camera, cameraPos, res.points, vertexConsumers);
        }
    }

    private record PathResult(List<Vec3d> points, HitResult finalHit, Entity hitEntity) {}

    private static PathResult simulatePath(PlayerEntity player, Item item, double yawOffsetRad) {
        // Vanilla-like initial spawn position offset near bow/crossbow
        float yaw = player.getYaw();
        float yawRad = (float) Math.toRadians(yaw);
        double spawnDx = -MathHelper.cos(yawRad) * 0.16;
        double spawnDz = -MathHelper.sin(yawRad) * 0.16;
        Vec3d start = new Vec3d(player.getX() + spawnDx, player.getEyeY() - 0.1, player.getZ() + spawnDz);

        Vec3d velocity = getInitialVelocity(player, item, yawOffsetRad);
        if (velocity.lengthSquared() < 1e-10) return new PathResult(List.of(), null, null);

        MinecraftClient client = MinecraftClient.getInstance();
        List<Vec3d> points = new ArrayList<>();
        Vec3d pos = start;
        Vec3d vel = velocity;

        HitResult finalHit = null;
        Entity hitEntity = null;

        int pointsAdded = 0;
        outer:
        for (;;) {
            for (int s = 0; s < SUBSTEPS_PER_TICK; s++) {
                points.add(pos);
                if (++pointsAdded >= HARD_POINT_LIMIT) break outer;

                Vec3d nextPos = pos.add(vel.multiply(1.0 / SUBSTEPS_PER_TICK));

                if (client.world == null) break outer;
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
        if (client.world == null) return false;
        // sample 3 points along the segment
        for (int i = 0; i <= 2; i++) {
            double t = i / 2.0;
            Vec3d p = a.lerp(b, t);
            BlockPos bp = BlockPos.ofFloored(p);
            if (client.world.getFluidState(bp) != null && client.world.getFluidState(bp).isIn(FluidTags.WATER)) return true;
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

    private static Vec3d getInitialVelocity(PlayerEntity player, Item item, double yawOffsetRad) {
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

    private static void renderLineAlongPath(MatrixStack matrices, MinecraftClient client, Camera camera, Vec3d cameraPos, List<Vec3d> points, VertexConsumerProvider provider) {
        if (points == null || points.isEmpty()) return;

        int total = points.size();
        int spawnCount = Math.min(MAX_PARTICLES_PER_FRAME, total);
        double inv = (double) total / (double) spawnCount;

        for (int s = 0; s < spawnCount; s++) {
            int idx = Math.min(total - 1, (int) Math.floor(s * inv));
            Vec3d p = points.get(idx);

            double distSq = p.squaredDistanceTo(cameraPos);
            if (distSq < MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE) continue;
            if (distSq > MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE) continue;

            // Spawn a single small END_ROD particle at the sampled point. This looks like a thin dot.
            client.particleManager.addParticle(ParticleTypes.END_ROD, p.x, p.y, p.z, 0.0, 0.0, 0.0);
        }
    }
}
