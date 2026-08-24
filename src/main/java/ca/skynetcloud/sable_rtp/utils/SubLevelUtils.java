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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SubLevelUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger("sable_rtp");
    private static final boolean DEBUG = false;

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

        if (DEBUG) {
            LOGGER.info("Starting location search for vessel type: {} in dimension: {}", vesselType, level.dimension().location());
            LOGGER.info("Search radius: {}, max attempts: {}, ground clearance: {}", TELEPORT_SEARCH_RADIUS, MAX_LOCATION_LOOKUP_ATTEMPTS, GROUND_CLEARANCE);
        }

        int rejectedCount = 0;
        int waterRejections = 0;
        int worldBorderRejections = 0;
        int worldBoundsRejections = 0;
        int terrainHeightRejections = 0;
        int safetyCheckRejections = 0;

        for (int attempt = 0; attempt < MAX_LOCATION_LOOKUP_ATTEMPTS; attempt++) {
            int x = rand.nextInt(TELEPORT_SEARCH_RADIUS * 2) - TELEPORT_SEARCH_RADIUS;
            int z = rand.nextInt(TELEPORT_SEARCH_RADIUS * 2) - TELEPORT_SEARCH_RADIUS;

            BlockPos testPos = new BlockPos(x, 0, z);

            if (!level.getWorldBorder().isWithinBounds(testPos)) {
                worldBorderRejections++;
                continue;
            }

            if (!level.isInWorldBounds(testPos)) {
                worldBoundsRejections++;
                continue;
            }

            level.getChunk(x >> 4, z >> 4);

            int destY;
            if (isInWater) {
                int getSurfaceHeightMap = getHighestTerrainUnderFootprint(level, x, z, halfSizeX, halfSizeZ);

                if (getSurfaceHeightMap <= level.getMinBuildHeight()) {
                    terrainHeightRejections++;
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

                int minAirHeightMap = Math.min(AIRSHIP_MIN_HEIGHT, availableHeight - 10);
                int maxAirHeightMap = Math.min(AIRSHIP_MAX_HEIGHT, availableHeight - 5);

                if (maxAirHeightMap <= minAirHeightMap) {
                    minAirHeightMap = Math.max(20, availableHeight / 4);
                    maxAirHeightMap = Math.max(minAirHeightMap + 10, availableHeight / 2);
                }

                if (maxAirHeightMap <= minAirHeightMap) {
                    minAirHeightMap = Math.max(10, availableHeight - 20);
                    maxAirHeightMap = Math.max(minAirHeightMap + 5, availableHeight - 10);
                }

                int airHeight = minAirHeightMap + rand.nextInt(Math.max(1, maxAirHeightMap - minAirHeightMap));
                destY = terrainHeight + airHeight;

                destY = Math.min(destY, maxY);
                destY = Math.max(destY, minY + 10);

            } else {
                BlockPos groundPos = findSolidGround(level, x, z);
                if (groundPos == null) {
                    terrainHeightRejections++;
                    continue;
                }

                if (groundPos.getY() < level.getSeaLevel() + 1) {
                    waterRejections++;
                    if (DEBUG && attempt % 10 == 0) {
                        LOGGER.debug("Rejected position at ({}, {}) - ground height {} is below sea level + 1", x, z, groundPos.getY());
                    }
                    continue;
                }

                destY = groundPos.getY() + 1;

                destY = Math.min(destY, maxY - 5);
                destY = Math.max(destY, minY + 5);
            }

            BlockPos finalPos = new BlockPos(x, destY, z);

            if (DEBUG && attempt % 25 == 0) {
                LOGGER.debug("Testing position at ({}, {}, {}) - attempt {}/{}", x, destY, z, attempt + 1, MAX_LOCATION_LOOKUP_ATTEMPTS);
            }

            if (isSafeDestination(level, finalPos, vesselType, halfSizeX, halfSizeZ)) {
                attemptsOut[0] = attempt + 1;
                if (DEBUG) {
                    LOGGER.info("Found safe destination at ({}, {}, {}) after {} attempts", x, destY, z, attempt + 1);
                    LOGGER.info("Rejection stats - Water: {}, WorldBorder: {}, WorldBounds: {}, TerrainHeight: {}, SafetyCheck: {}",
                            waterRejections, worldBorderRejections, worldBoundsRejections, terrainHeightRejections, safetyCheckRejections);
                }
                return finalPos;
            } else {
                safetyCheckRejections++;
                rejectedCount++;
                if (DEBUG && rejectedCount % 5 == 0) {
                    LOGGER.debug("Position at ({}, {}, {}) failed safety check - rejection count: {}", x, destY, z, rejectedCount);
                }
            }
        }

        attemptsOut[0] = MAX_LOCATION_LOOKUP_ATTEMPTS;

        if (DEBUG) {
            LOGGER.warn("Failed to find safe destination after {} attempts", MAX_LOCATION_LOOKUP_ATTEMPTS);
            LOGGER.warn("Rejection stats - Water: {}, WorldBorder: {}, WorldBounds: {}, TerrainHeight: {}, SafetyCheck: {}",
                    waterRejections, worldBorderRejections, worldBoundsRejections, terrainHeightRejections, safetyCheckRejections);
            LOGGER.warn("Total rejected positions: {}", rejectedCount);
            LOGGER.warn("Vessel type: {}, Dimension: {}", vesselType, level.dimension().location());
        }

        return null;
    }

    private static boolean isSolidBlock(ServerLevel level, BlockPos pos) {
        if (!level.isInWorldBounds(pos)) return false;
        var block = level.getBlockState(pos).getBlock();
        return block != Blocks.AIR && block != Blocks.CAVE_AIR && block != Blocks.VOID_AIR;
    }

    private static BlockPos findSolidGround(ServerLevel level, int centerX, int centerZ) {
        int[] offsets = {-1, 0, 1};
        int bestHeight = Integer.MIN_VALUE;
        BlockPos bestPos = null;

        for (int destx : offsets) {
            for (int destz : offsets) {
                int sampleX = centerX + (int) (destx * 2);
                int sampleZ = centerZ + (int) (destz * 2);

                BlockPos samplePos = new BlockPos(sampleX, 0, sampleZ);
                if (!level.isInWorldBounds(samplePos)) continue;

                int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX, sampleZ);

                if (height <= level.getMinBuildHeight()) continue;

                BlockPos groundPos = new BlockPos(sampleX, height, sampleZ);
                BlockPos belowPos = groundPos.below();

                if (!level.isInWorldBounds(belowPos)) continue;

                if (isWaterObstructed(level, groundPos)) continue;

                boolean isSolidAt = isSolidBlock(level, groundPos);
                boolean isSolidBelow = isSolidBlock(level, belowPos);

                if (isSolidAt && isSolidBelow && height > bestHeight) {
                    bestHeight = height;
                    bestPos = groundPos;
                }
            }
        }

        if (bestPos == null) {
            for (int destx : offsets) {
                for (int destz : offsets) {
                    int sampleX = centerX + (int) (destx * 2);
                    int sampleZ = centerZ + (int) (destz * 2);

                    BlockPos samplePos = new BlockPos(sampleX, 0, sampleZ);
                    if (!level.isInWorldBounds(samplePos)) continue;

                    int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX, sampleZ);

                    if (height <= level.getMinBuildHeight()) continue;

                    for (int y = height; y > level.getSeaLevel() - 10; y--) {
                        BlockPos checkPos = new BlockPos(sampleX, y, sampleZ);
                        if (!level.isInWorldBounds(checkPos)) continue;

                        if (isSolidBlock(level, checkPos)) {
                            BlockPos abovePos = checkPos.above();
                            if (level.isInWorldBounds(abovePos) && isSolidBlock(level, abovePos)) {
                                if (y > bestHeight) {
                                    bestHeight = y;
                                    bestPos = abovePos;
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (bestPos == null) {
            for (int destx : offsets) {
                for (int destz : offsets) {
                    int sampleX = centerX + (int) (destx * 2);
                    int sampleZ = centerZ + (int) (destz * 2);

                    BlockPos samplePos = new BlockPos(sampleX, 0, sampleZ);
                    if (!level.isInWorldBounds(samplePos)) continue;

                    int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sampleX, sampleZ);

                    if (height <= level.getMinBuildHeight()) continue;

                    for (int y = height; y > level.getSeaLevel() - 10; y--) {
                        BlockPos checkPos = new BlockPos(sampleX, y, sampleZ);
                        if (!level.isInWorldBounds(checkPos)) continue;

                        if (isSolidBlock(level, checkPos)) {
                            if (y > bestHeight) {
                                bestHeight = y;
                                bestPos = checkPos.above();
                            }
                            break;
                        }
                    }
                }
            }
        }

        return bestPos;
    }

    private static int getHighestTerrainUnderFootprint(ServerLevel level, int centerX, int centerZ, double halfSizeX, double halfSizeZ) {

        int[] offsets = {-1, 0, 1};
        int maxHeight = Integer.MIN_VALUE;
        boolean foundValidHeight = false;

        for (int destx : offsets) {
            for (int destz : offsets) {
                int sampleX = centerX + (int) (destx * halfSizeX);
                int sampleZ = centerZ + (int) (destz * halfSizeZ);

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
        GROUND, AIRSHIP, BOAT
    }

    private static boolean isWaterObstructed(ServerLevel level, BlockPos pos) {
        var fluid = level.getFluidState(pos).getType();
        return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
    }

    private static double getHighestWaterRatio(ServerLevel level, BlockPos center, double halfSizeX, double halfSizeZ) {
        int[] offsets = {-1, 0, 1};
        int total = 0;
        int waterCount = 0;

        for (int destx : offsets) {
            for (int destz : offsets) {
                int sampleX = center.getX() + (int) (destx * halfSizeX);
                int sampleZ = center.getZ() + (int) (destz * halfSizeZ);
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

    private static boolean isNearByWater(ServerLevel level, BlockPos center, double halfSizeX, double halfSizeZ) {
        int[] offsets = {-1, 0, 1};
        int waterCount = 0;
        int totalChecks = 0;

        for (int destx : offsets) {
            for (int destz : offsets) {
                int sampleX = center.getX() + (int) (destx * halfSizeX * 2);
                int sampleZ = center.getZ() + (int) (destz * halfSizeZ * 2);

                BlockPos samplePos = new BlockPos(sampleX, center.getY(), sampleZ);
                if (!level.isInWorldBounds(samplePos)) continue;

                totalChecks++;
                if (isWaterObstructed(level, samplePos)) {
                    waterCount++;
                }
            }
        }

        return totalChecks > 0 && (double) waterCount / totalChecks > 0.15;
    }

    private static boolean isSafeDestination(ServerLevel level, BlockPos pos, VesselType vesselType, double halfSizeX, double halfSizeZ) {
        if (!level.isInWorldBounds(pos)) {
            if (DEBUG) LOGGER.debug("Safety check failed: Position out of world bounds");
            return false;
        }

        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            if (DEBUG) LOGGER.debug("Safety check failed: Chunk not loaded at {}", pos);
            return false;
        }

        if (pos.getY() >= level.getMaxBuildHeight() - 5) {
            if (DEBUG) LOGGER.debug("Safety check failed: Position too high at {}", pos);
            return false;
        }

        if (pos.getY() <= level.getMinBuildHeight() + 5) {
            if (DEBUG) LOGGER.debug("Safety check failed: Position too low at {}", pos);
            return false;
        }

        return switch (vesselType) {
            case GROUND -> {
                if (pos.getY() < level.getSeaLevel() + 1) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Height {} below sea level + 1 at {}", pos.getY(), pos);
                    yield false;
                }

                BlockPos belowPos = pos.below();
                if (!level.isInWorldBounds(belowPos)) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Below position out of bounds at {}", pos);
                    yield false;
                }

                if (!isSolidBlock(level, belowPos)) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Below block is not solid at {} - found {}", pos, level.getBlockState(belowPos).getBlock().getName().getString());
                    yield false;
                }

                if (isWaterObstructed(level, pos) || isWaterObstructed(level, pos.above())) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Water obstruction at {}", pos);
                    yield false;
                }

                if (isNearByWater(level, pos, halfSizeX, halfSizeZ)) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Too close to water at {}", pos);
                    yield false;
                }

                if (isWaterObstructed(level, belowPos)) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Water below at {}", pos);
                    yield false;
                }

                if (!level.getBlockState(pos).isAir()) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Block not air at {} - found {}", pos, level.getBlockState(pos).getBlock().getName().getString());
                    yield false;
                }

                int fieldCheckRadius = 2;
                int requiredClearance = 3;
                int totalChecks = 0;
                int obstacleCount = 0;
                int treeCount = 0;
                boolean hasTreeInCenter = false;

                for (int dx = -fieldCheckRadius; dx <= fieldCheckRadius; dx++) {
                    for (int dz = -fieldCheckRadius; dz <= fieldCheckRadius; dz++) {
                        int checkX = pos.getX() + dx;
                        int checkZ = pos.getZ() + dz;
                        BlockPos checkPos = new BlockPos(checkX, pos.getY(), checkZ);

                        if (!level.isInWorldBounds(checkPos)) continue;
                        totalChecks++;

                        BlockPos checkBelow = checkPos.below();
                        if (level.isInWorldBounds(checkBelow)) {
                            if (isSolidBlock(level, checkBelow)) {
                                if (isWaterObstructed(level, checkPos)) {
                                    if (DEBUG) LOGGER.debug("Ground safety failed: Water in field at {}", checkPos);
                                    yield false;
                                }
                            }
                        }

                        if (!level.getBlockState(checkPos).isAir()) {
                            var block = level.getBlockState(checkPos).getBlock();
                            String blockName = block.getName().getString().toLowerCase();

                            boolean isTree = blockName.contains("log") || blockName.contains("wood") ||
                                    blockName.contains("leaves") || blockName.contains("leaf");

                            if (isTree) {
                                treeCount++;
                                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                                    hasTreeInCenter = true;
                                }
                            }

                            if (!blockName.contains("grass") && !blockName.contains("flower") &&
                                    !blockName.contains("tall") && !blockName.contains("fern") &&
                                    !blockName.contains("sapling") && !blockName.contains("carpet") &&
                                    !isTree) {
                                obstacleCount++;
                            }
                        }

                        for (int height = 1; height <= requiredClearance; height++) {
                            BlockPos aboveCheck = new BlockPos(checkX, pos.getY() + height, checkZ);
                            if (level.isInWorldBounds(aboveCheck) && !level.getBlockState(aboveCheck).isAir()) {
                                var block = level.getBlockState(aboveCheck).getBlock();
                                String blockName = block.getName().getString().toLowerCase();
                                if (blockName.contains("log") || blockName.contains("wood") ||
                                        blockName.contains("leaves") || blockName.contains("leaf")) {
                                    if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                                        hasTreeInCenter = true;
                                    }
                                    treeCount++;
                                }
                                obstacleCount++;
                                break;
                            }
                        }
                    }
                }

                if (hasTreeInCenter) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Tree in center at {}", pos);
                    yield false;
                }

                double obstructionPercentage = totalChecks > 0 ? (double) obstacleCount / totalChecks : 1.0;

                if (obstructionPercentage > 0.6) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Obstruction percentage {} too high at {}", obstructionPercentage, pos);
                    yield false;
                }

                if (treeCount > 6) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Tree count {} too high at {}", treeCount, pos);
                    yield false;
                }

                int groundY = pos.getY() - 1;
                int heightVariation = 0;
                int groundChecks = 0;

                for (int destx = -1; destx <= 1; destx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos groundCheck = new BlockPos(pos.getX() + destx, groundY, pos.getZ() + dz);
                        if (!level.isInWorldBounds(groundCheck)) continue;

                        groundChecks++;
                        if (isWaterObstructed(level, groundCheck)) {
                            if (DEBUG) LOGGER.debug("Ground safety failed: Water at ground level {}", groundCheck);
                            yield false;
                        }
                        if (level.getBlockState(groundCheck).isAir()) {
                            BlockPos belowGround = groundCheck.below();
                            if (level.isInWorldBounds(belowGround) && level.getBlockState(belowGround).isAir()) {
                                heightVariation++;
                            }
                        }
                    }
                }

                if (groundChecks > 0 && (double) heightVariation / groundChecks > 0.7) {
                    if (DEBUG) LOGGER.debug("Ground safety failed: Ground variation too high at {}", pos);
                    yield false;
                }

                boolean finalCheck = level.getBlockState(pos.above()).isAir() &&
                        level.getBlockState(pos.above(2)).isAir() &&
                        level.getBlockState(pos.above(3)).isAir();

                if (!finalCheck && DEBUG) {
                    LOGGER.debug("Ground safety failed: Not enough clearance above at {}", pos);
                }

                yield finalCheck;
            }
            case AIRSHIP -> {
                int spaceNeeded = 10;
                for (int i = 0; i < spaceNeeded; i++) {
                    BlockPos getCheckPos = pos.above(i);
                    if (!level.isInWorldBounds(getCheckPos)) {
                        if (DEBUG) LOGGER.debug("AIRSHIP safety failed: Out of bounds at {}", getCheckPos);
                        yield false;
                    }
                    if (!level.getBlockState(getCheckPos).isAir()) {
                        if (DEBUG) LOGGER.debug("AIRSHIP safety failed: Block at {}", getCheckPos);
                        yield false;
                    }
                }
                yield true;
            }
            case BOAT -> {
                if (!isWaterObstructed(level, pos)) {
                    if (DEBUG) LOGGER.debug("BOAT safety failed: Not in water at {}", pos);
                    yield false;
                }

                if (getHighestWaterRatio(level, pos, halfSizeX, halfSizeZ) < 0.85) {
                    if (DEBUG) LOGGER.debug("BOAT safety failed: Not enough water at {}", pos);
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
                    if (DEBUG) LOGGER.debug("BOAT safety failed: Water depth too shallow at {}", pos);
                    yield false;
                }

                for (int i = 1; i <= 5; i++) {
                    BlockPos above = pos.above(i);
                    if (!level.isInWorldBounds(above)) {
                        if (DEBUG) LOGGER.debug("BOAT safety failed: Out of bounds above at {}", above);
                        yield false;
                    }
                    if (!level.getBlockState(above).isAir() && !isWaterObstructed(level, above)) {
                        if (DEBUG) LOGGER.debug("BOAT safety failed: Block above at {}", above);
                        yield false;
                    }
                }

                yield true;
            }
        };
    }
}