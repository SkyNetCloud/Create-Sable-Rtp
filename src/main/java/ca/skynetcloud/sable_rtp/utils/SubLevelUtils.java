package ca.skynetcloud.sable_rtp.utils;

import ca.skynetcloud.sable_rtp.Config;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;

public class SubLevelUtils {

    private static final int TELEPORT_SEARCH_RADIUS = Config.getTeleportSearchRadius();
    private static final int MAX_LOCATION_LOOKUP_ATTEMPTS = Config.getMaxLocationLookupAttempts();
    private static final int GROUND_CLEARANCE = Config.getGroundClearance();
    private static final int AIRSHIP_MIN_HEIGHT = Config.getAirshipMinHeight();
    private static final int AIRSHIP_MAX_HEIGHT = Config.getAirshipMaxHeight();

    public static SubLevel resolve(ServerPlayer player) {
        Entity vehicle = player.getVehicle();
        if (vehicle != null) {
            SubLevel viaVehicle = Sable.HELPER.getTrackingSubLevel(vehicle);
            if (viaVehicle != null) return viaVehicle;
        }

        return Sable.HELPER.getTrackingSubLevel(player);
    }

    public static boolean isAirborne(ServerSubLevel subLevel) {
        BoundingBox3dc bounds = subLevel.boundingBox();
        ServerLevel level = subLevel.getLevel();

        double lowestPoint = bounds.minY();

        double centerX = (bounds.minX() + bounds.maxX()) / 2.0;
        double centerZ = (bounds.minZ() + bounds.maxZ()) / 2.0;

        int terrainHeight = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) centerX, (int) centerZ);

        return lowestPoint - terrainHeight > 5.0;
    }

    public static boolean isInWater(ServerSubLevel subLevel) {
        BoundingBox3dc bounds = subLevel.boundingBox();

        double minX = bounds.minX();
        double maxX = bounds.maxX();
        double minZ = bounds.minZ();
        double maxZ = bounds.maxZ();
        double minY = bounds.minY();

        ServerLevel level = subLevel.getLevel();

        double[][] samplePoints = {{minX, minZ}, {maxX, minZ}, {minX, maxZ}, {maxX, maxZ}, {(minX + maxX) / 2, (minZ + maxZ) / 2}};

        int waterCount = 0;
        int totalSamples = samplePoints.length;

        for (double[] point : samplePoints) {
            int x = (int) point[0];
            int z = (int) point[1];
            int y = (int) minY;

            BlockPos checkPos = new BlockPos(x, y, z);

            if (!level.isInWorldBounds(checkPos)) {
                continue;
            }

            if (level.getFluidState(checkPos).getType() == Fluids.WATER || level.getFluidState(checkPos).getType() == Fluids.FLOWING_WATER) {
                waterCount++;
            }
        }

        return (double) waterCount / totalSamples > 0.6;
    }



    public static BlockPos locateSafeTeleportPos(ServerLevel level, ServerSubLevel subLevel, boolean isAirship, boolean isInWater, int[] attemptsOut) {
        RandomSource rand = level.getRandom();

        BoundingBox3dc bounds = subLevel.boundingBox();
        Pose3d pose = subLevel.logicalPose();

        double pivotY = pose.position().y();

        double belowPivot = pivotY - bounds.minY();

        double halfSizeX = (bounds.maxX() - bounds.minX()) / 2.0;
        double halfSizeZ = (bounds.maxZ() - bounds.minZ()) / 2.0;

        int minY = level.getMinBuildHeight() + 1;
        int maxY = level.getMaxBuildHeight() - 5;

        VesselType vesselType = isInWater ? VesselType.BOAT : (isAirship ? VesselType.AIRSHIP : VesselType.GROUND);

        for (int attempt = 0; attempt < MAX_LOCATION_LOOKUP_ATTEMPTS; attempt++) {
            int x = rand.nextInt(TELEPORT_SEARCH_RADIUS * 2) - TELEPORT_SEARCH_RADIUS;
            int z = rand.nextInt(TELEPORT_SEARCH_RADIUS * 2) - TELEPORT_SEARCH_RADIUS;

            BlockPos testPos = new BlockPos(x, 0, z);

            if (!level.getWorldBorder().isWithinBounds(testPos)) {
                continue;
            }

            if (!level.isInWorldBounds(testPos)) {
                continue;
            }

            level.getChunk(x >> 4, z >> 4);

            int destY;
            if (isInWater) {
                int getSurfaceHeightMap = getHighestTerrainUnderFootprint(level, x, z, halfSizeX, halfSizeZ);

                if (getSurfaceHeightMap <= level.getMinBuildHeight()) {
                    continue;
                }

                destY = getSurfaceHeightMap - 1;

                destY = Math.min(destY, maxY);
                destY = Math.max(destY, minY);

            } else if (isAirship) {

                int terrainHeight = getHighestTerrainUnderFootprint(level, x, z, halfSizeX, halfSizeZ);

                if (terrainHeight <= level.getMinBuildHeight()) {
                    terrainHeight = level.getSeaLevel();
                }

                int availableHeight = maxY - terrainHeight;

                int minAirHeight = Math.min(AIRSHIP_MIN_HEIGHT, availableHeight - 10);
                int maxAirHeight = Math.min(AIRSHIP_MAX_HEIGHT, availableHeight - 5);

                if (maxAirHeight <= minAirHeight) {
                    minAirHeight = Math.max(20, availableHeight / 4);
                    maxAirHeight = Math.max(minAirHeight + 10, availableHeight / 2);
                }

                if (maxAirHeight <= minAirHeight) {
                    minAirHeight = Math.max(10, availableHeight - 20);
                    maxAirHeight = Math.max(minAirHeight + 5, availableHeight - 10);
                }

                int airHeight = minAirHeight + rand.nextInt(Math.max(1, maxAirHeight - minAirHeight));
                destY = terrainHeight + airHeight;

                destY = Math.min(destY, maxY);
                destY = Math.max(destY, minY + 10);

            } else {
                int maxTerrainHeight = getHighestTerrainUnderFootprint(level, x, z, halfSizeX, halfSizeZ);
                if (maxTerrainHeight <= level.getMinBuildHeight()) {
                    continue;
                }
                destY = (int) Math.ceil(maxTerrainHeight + belowPivot + GROUND_CLEARANCE);

                destY = Math.min(destY, maxY);
                destY = Math.max(destY, minY);
            }

            BlockPos finalPos = new BlockPos(x, destY, z);

            if (isSafeDestination(level, finalPos, vesselType, halfSizeX, halfSizeZ)) {
                attemptsOut[0] = attempt + 1;
                return finalPos;
            }
        }

        attemptsOut[0] = MAX_LOCATION_LOOKUP_ATTEMPTS;
        return null;
    }

    private static int getHighestTerrainUnderFootprint(ServerLevel level, int centerX, int centerZ, double halfSizeX, double halfSizeZ) {

        int[] offsets = {-1, 0, 1};
        int maxHeight = Integer.MIN_VALUE;
        boolean foundValidHeight = false;

        for (int dx : offsets) {
            for (int dz : offsets) {
                int sampleX = centerX + (int) (dx * halfSizeX);
                int sampleZ = centerZ + (int) (dz * halfSizeZ);

                BlockPos samplePos = new BlockPos(sampleX, 0, sampleZ);

                if (level.isInWorldBounds(samplePos)) {
                    int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX, sampleZ);
                    if (height > level.getMinBuildHeight()) {
                        maxHeight = Math.max(maxHeight, height);
                        foundValidHeight = true;
                    }
                }
            }
        }

        if (!foundValidHeight) {
            maxHeight = level.getSeaLevel();
        }

        return maxHeight;
    }

    public enum VesselType {
        GROUND,AIRSHIP,BOAT
    }

    private static boolean isWaterObstructed(ServerLevel level, BlockPos pos) {
        var fluid = level.getFluidState(pos).getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static double getHighestWaterRatio(ServerLevel level, BlockPos center, double halfSizeX, double halfSizeZ) {
        int[] offsets = {-1, 0, 1};
        int total = 0;
        int waterCount = 0;

        for (int dx : offsets) {
            for (int dz : offsets) {
                int sampleX = center.getX() + (int) (dx * halfSizeX);
                int sampleZ = center.getZ() + (int) (dz * halfSizeZ);
                BlockPos samplePos = new BlockPos(sampleX, center.getY(), sampleZ);

                if (!level.isInWorldBounds(samplePos)) continue;

                total++;
                if (isWaterObstructed(level, samplePos)) {
                    waterCount++;
                }
            }
        }

        return total == 0 ? 0.0 : (double) waterCount / total;
    }


    private static boolean isSafeDestination(ServerLevel level, BlockPos pos, VesselType vesselType, double halfSizeX, double halfSizeZ) {
        if (!level.isInWorldBounds(pos)) {
            return false;
        }

        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }

        if (pos.getY() >= level.getMaxBuildHeight() - 5) {
            return false;
        }

        if (pos.getY() <= level.getMinBuildHeight() + 5) {
            return false;
        }

        return switch (vesselType) {
            case GROUND -> {
                if (pos.getY() < level.getSeaLevel()) {
                    yield false;
                }
                if (!level.getBlockState(pos).isAir()) {
                    yield false;
                }
                BlockPos abovePos = pos.above();
                yield level.getBlockState(abovePos).isAir();
            }
            case AIRSHIP -> {
                int spaceNeeded = 10;
                for (int i = 0; i < spaceNeeded; i++) {
                    BlockPos getCheckPos = pos.above();
                    if (!level.isInWorldBounds(getCheckPos)){

                    }
                }
                yield true;
            }
            case  BOAT -> {
                if (!isWaterObstructed(level, pos)) {
                    yield false;
                }

                if (getHighestWaterRatio(level, pos, halfSizeX, halfSizeZ) < 0.85) {
                    yield false;
                }


                int waterDepth = 0;
                for (int i = 0; i < 4; i++) {
                    BlockPos below = pos.below(i + 1);
                    if (!level.isInWorldBounds(below)) break;
                    if (isWaterObstructed(level, below)) {
                        waterDepth++;
                    } else {
                        break;
                    }
                }
                if (waterDepth < 4) {
                    yield false;
                }

                for (int i = 1; i <= 5; i++) {
                    BlockPos above = pos.above(i);
                    if (!level.isInWorldBounds(above)) yield false;
                    if (!level.getBlockState(above).isAir() && !isWaterObstructed(level, above)) {
                        yield false;
                    }
                }

                yield true;
            }

        };

    }

}