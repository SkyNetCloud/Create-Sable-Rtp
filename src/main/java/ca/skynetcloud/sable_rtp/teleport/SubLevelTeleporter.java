package ca.skynetcloud.sable_rtp.teleport;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class SubLevelTeleporter {

    private static final Logger LOGGER = LoggerFactory.getLogger("sable_rtp");

    public static boolean teleport(ServerSubLevel subLevel, ServerLevel level, BlockPos dest) {
        if (subLevel == null) {
            return false;
        }

        try {
            // Get current pose
            Pose3d currentPose = subLevel.logicalPose();
            Quaterniond currentOrientation = currentPose.orientation();
            Vector3d currentPos = currentPose.position();

            // Calculate the offset
            double dx = dest.getX() + 0.5 - currentPos.x;
            double dy = dest.getY() - currentPos.y;
            double dz = dest.getZ() + 0.5 - currentPos.z;

            Vector3d newPosition = new Vector3d(
                    dest.getX() + 0.5,
                    dest.getY(),
                    dest.getZ() + 0.5
            );

            // Get all players inside the sublevel
            List<ServerPlayer> playersToTeleport = new ArrayList<>();
            var bounds = subLevel.boundingBox();

            for (UUID uuid : subLevel.getTrackingPlayers()) {
                ServerPlayer player = (ServerPlayer) level.getPlayerByUUID(uuid);
                if (player == null) {
                    continue;
                }

                Vec3 playerPos = player.position();
                if (playerPos.x >= bounds.minX() && playerPos.x <= bounds.maxX() &&
                        playerPos.y >= bounds.minY() && playerPos.y <= bounds.maxY() &&
                        playerPos.z >= bounds.minZ() && playerPos.z <= bounds.maxZ()) {
                    playersToTeleport.add(player);
                }
            }

            // Store vehicle info for each player
            Map<ServerPlayer, Entity> playerVehicles = new HashMap<>();
            Map<ServerPlayer, Vec3> playerRelativeToVehicle = new HashMap<>();

            for (ServerPlayer player : playersToTeleport) {
                if (player.getVehicle() != null) {
                    Entity vehicle = player.getVehicle();
                    playerVehicles.put(player, vehicle);
                    // Store relative position to vehicle
                    Vec3 relative = player.position().subtract(vehicle.position());
                    playerRelativeToVehicle.put(player, relative);
                }
            }

            // Teleport the sublevel physics
            PhysicsPipeline pipeline = SubLevelPhysicsSystem.require(level).getPipeline();
            pipeline.teleport(subLevel, newPosition, currentOrientation);

            // Teleport all players
            for (ServerPlayer player : playersToTeleport) {
                Entity vehicle = playerVehicles.get(player);

                if (vehicle != null && vehicle.isAlive()) {
                    // Teleport the vehicle with offset
                    vehicle.teleportTo(
                            level,
                            vehicle.getX() + dx,
                            vehicle.getY() + dy,
                            vehicle.getZ() + dz,
                            Set.of(),
                            vehicle.getYRot(),
                            vehicle.getXRot()
                    );

                    // Teleport player to maintain relative position to vehicle
                    Vec3 relative = playerRelativeToVehicle.get(player);
                    if (relative != null) {
                        player.teleportTo(
                                level,
                                vehicle.getX() + relative.x,
                                vehicle.getY() + relative.y,
                                vehicle.getZ() + relative.z,
                                Set.of(),
                                player.getYRot(),
                                player.getXRot()
                        );
                    }

                    // Ensure player is riding the vehicle
                    if (player.getVehicle() == null) {
                        player.startRiding(vehicle);
                    }
                } else {
                    // Not riding - teleport player with same offset
                    player.teleportTo(
                            level,
                            player.getX() + dx,
                            player.getY() + dy,
                            player.getZ() + dz,
                            Set.of(),
                            player.getYRot(),
                            player.getXRot()
                    );
                }
            }

            // Log the teleport
            String subLevelName = subLevel.getName();
            if (subLevelName == null) {
                subLevelName = "unnamed";
            }

            LOGGER.info(
                    "Teleported sub-level '{}' (id={}) to {} in {} with {} players",
                    subLevelName,
                    subLevel.getUniqueId(),
                    dest,
                    level.dimension().location(),
                    playersToTeleport.size()
            );

            return true;

        } catch (Exception e) {
            LOGGER.error("Sub-level teleport failed", e);
            return false;
        }
    }
}