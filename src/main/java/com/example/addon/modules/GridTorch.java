package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.block.*;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.Heightmap;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.BiConsumer;

/**
 * GridTorch — Meteor module that walks a rectangular grid and places torches at regular intervals.
 *
 * Features implemented:
 * - Precomputes a rectangular grid (row-major, zigzag) from player's origin
 * - Respects x/z spacing and max distance bounds
 * - Water boundary stopping per-column (when enabled)
 * - maxDip enforcement between consecutive *accepted* grid points
 * - Break flora/tree obstruction (configurable)
 * - Optionally place a support block when surface cannot accept a torch
 * - Precompute + render preview of valid and skipped positions
 * - Uses Baritone (reflectively) for navigation (safe when Baritone absent)
 * - Placement uses client interaction manager (simulated right-click / attack)
 * - Queue-based traversal, robust skip-on-failure behavior
 *
 * Notes:
 * - Designed to compile and safely noop/disable if Baritone is missing
 * - Visualization renders precomputed positions only
 */
public class GridTorch extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGrid = settings.createGroup("Grid");
    private final SettingGroup sgTerrain = settings.createGroup("Terrain");
    private final SettingGroup sgBlocks = settings.createGroup("Block Interaction");
    private final SettingGroup sgVisual = settings.createGroup("Visual");

    // Grid spacing
    public final Setting<Integer> xSpacing = sgGrid.add(new IntSetting.Builder()
        .name("x-spacing")
        .description("Distance between torches along X axis")
        .defaultValue(8)
        .min(1)
        .build()
    );

    public final Setting<Integer> zSpacing = sgGrid.add(new IntSetting.Builder()
        .name("z-spacing")
        .description("Distance between torches along Z axis")
        .defaultValue(8)
        .min(1)
        .build()
    );

    public final Setting<Integer> maxDistX = sgGrid.add(new IntSetting.Builder()
        .name("max-dist-x")
        .description("Maximum travel distance in X from origin")
        .defaultValue(128)
        .min(1)
        .build()
    );

    public final Setting<Integer> maxDistZ = sgGrid.add(new IntSetting.Builder()
        .name("max-dist-z")
        .description("Maximum travel distance in Z from origin")
        .defaultValue(128)
        .min(1)
        .build()
    );

    // Traversal pattern: nearest-first (current) or outward spiral from origin
    public final Setting<Boolean> spiralTraversal = sgGrid.add(new BoolSetting.Builder()
        .name("spiral-traversal")
        .description("If true, traverse valid torch positions in an outward spiral from the origin; otherwise use the default nearest-first route")
        .defaultValue(false)
        .build()
    );

    // Terrain constraints
    public final Setting<Boolean> stopOnWater = sgTerrain.add(new BoolSetting.Builder()
        .name("water-boundary")
        .description("If true, stop grid expansion in a direction when water is encountered")
        .defaultValue(true)
        .build()
    );

    public final Setting<Integer> maxDip = sgTerrain.add(new IntSetting.Builder()
        .name("max-dip")
        .description("Maximum allowed Y difference between consecutive accepted grid placements")
        .defaultValue(5)
        .min(0)
        .build()
    );

    public final Setting<Integer> minLightLevel = sgTerrain.add(new IntSetting.Builder()
        .name("min-light-level")
        .description("Skip placement if block light >= this value (0-15). Set to 0 to never skip based on light")
        .defaultValue(8)
        .min(0)
        .max(15)
        .build()
    );

    public final Setting<Boolean> avoidPlayerBlocks = sgTerrain.add(new BoolSetting.Builder()
        .name("avoid-player-blocks")
        .description("Avoid placing torches within 3 chunks of suspicious player-made blocks (crafting tables, chests, redstone, etc.)")
        .defaultValue(false)
        .build()
    );

    // Avoid existing torches nearby (configurable radius)
    public final Setting<Boolean> avoidTorchesNearby = sgTerrain.add(new BoolSetting.Builder()
        .name("avoid-torches-nearby")
        .description("If true, skip any candidate that has an existing torch within the configured radius")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> avoidTorchesRadius = sgTerrain.add(new IntSetting.Builder()
        .name("avoid-torches-radius")
        .description("Radius (blocks) to scan for existing torches when \"avoid-torches-nearby\" is enabled")
        .defaultValue(12)
        .min(1)
        .max(64)
        .build()
    );

    // Block interaction
    public final Setting<Boolean> breakGrass = sgBlocks.add(new BoolSetting.Builder()
        .name("break-grass")
        .description("Break tall/short grass, flowers and replaceable flora before placing torch")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> breakTree = sgBlocks.add(new BoolSetting.Builder()
        .name("break-tree")
        .description("If true: break logs/leaves obstructing placement; otherwise skip that grid point")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> makeBlockLegal = sgBlocks.add(new BoolSetting.Builder()
        .name("make-block-legal")
        .description("Place a solid block under the torch if surface cannot accept it")
        .defaultValue(true)
        .build()
    );

    // Visual
    public final Setting<Boolean> visualize = sgVisual.add(new BoolSetting.Builder()
        .name("visualize")
        .description("Render bounding boxes at every valid grid placement position")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> validColor = sgVisual.add(new ColorSetting.Builder()
        .name("valid-color")
        .description("Color for valid placement positions")
        .defaultValue(Color.GREEN)
        .build()
    );

    private final Setting<SettingColor> skippedColor = sgVisual.add(new ColorSetting.Builder()
        .name("skipped-color")
        .description("Color for skipped/invalid placement positions")
        .defaultValue(Color.RED)
        .build()
    );

    // Preview-only highlight for positions that will require a support block (yellow)
    private final Setting<SettingColor> supportColor = sgVisual.add(new ColorSetting.Builder()
        .name("support-color")
        .description("Color for positions that require a support block (preview only)")
        .defaultValue(Color.YELLOW)
        .build()
    );

    // Internal state
    private BlockPos originPos;
    private RegistryKey<World> originDim;

    private final List<BlockPos> precomputed = new ArrayList<>();         // all candidate base positions (ground)
    private final List<BlockPos> validPositions = new ArrayList<>();      // accepted placements (unsorted — used for preview)
    private final List<BlockPos> skippedPositions = new ArrayList<>();    // rejected placements (visualization)
    private final List<BlockPos> supportNeededPositions = new ArrayList<>(); // positions that will need a support block
    private final Set<BlockPos> suspiciousBlocks = new HashSet<>();       // detected player/artificial blocks (scan area)

    private final List<BlockPos> orderedPositions = new ArrayList<>();    // optimized traversal order (nearest-first)
    private final Deque<BlockPos> queue = new ArrayDeque<>();             // work queue (orderedPositions)

    private int lastAcceptedY = Integer.MIN_VALUE; // Y of last accepted placement (for maxDip)
    private int torchesPlaced = 0;

    // Preview-only mode (renders precomputed grid without starting navigation/placement)
    private boolean previewing = false;
    private boolean previewOnlyMode = false;

    // Pause state: when out of resources (torches) we keep the module active but paused so
    // the user can refill and resume later without losing the computed queue.
    private boolean paused = false;

    /** Returns true when a visual preview (no placement) is active. */
    public boolean isPreviewing() { return previewing; }

    /** Returns true when the module was enabled solely for preview (no navigation/placement). */
    public boolean isPreviewOnly() { return previewOnlyMode; }

    /** Returns true when the module is paused (preserves queue/state). */
    public boolean isPaused() { return paused; }

    // Active target state
    private BlockPos currentPlacementTarget = null; // ground block under where the torch should be placed
    private BlockPos currentNavTarget = null;       // blockpos we asked Baritone to walk to (adjacent)
    private int currentTargetAgeTicks = 0;
    private boolean lastSubmissionAccepted = false;
    private int retryCountForCurrentTarget = 0;

    private static final int CURRENT_TARGET_GRACE_TICKS = 40; // grace before marking unreachable
    private static final int MAX_CURRENT_TARGET_RETRIES = 1;

    public GridTorch() {
        super(AddonTemplate.CATEGORY, "grid-torch", "Automatically walk a rectangular grid and place torches at configurable spacing.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            info("Player or world not present — cannot start GridTorch");
            toggle();
            return;
        }

        // If we're being enabled as preview-only, initialize preview state and skip Baritone/navigation setup.
        if (previewOnlyMode) {
            previewing = true;
            originPos = mc.player.getBlockPos();
            originDim = mc.world.getRegistryKey();

            precomputed.clear();
            validPositions.clear();
            skippedPositions.clear();
            queue.clear();

            // For preview we disable the dip-anchor so the first surface candidates aren't rejected
            lastAcceptedY = Integer.MIN_VALUE;
            torchesPlaced = 0;

            precomputeGrid();
            info("GridTorch preview: valid=" + validPositions.size() + " skipped=" + skippedPositions.size() + " supportNeeded=" + supportNeededPositions.size() + " (route=" + orderedPositions.size() + ")");
            return;
        }

        Object primaryBaritone = getPrimaryBaritone();
        if (primaryBaritone == null) {
            info("Baritone not installed — GridTorch disabled");
            toggle();
            return;
        }

        // Cancel any existing Baritone pathing
        try { if (baritoneIsPathing(primaryBaritone)) baritoneCancel(primaryBaritone); } catch (Throwable ignored) {}

        // disable any preview mode when actually activating the module
        previewing = false;

        originPos = mc.player.getBlockPos();
        originDim = mc.world.getRegistryKey();

        precomputed.clear();
        validPositions.clear();
        skippedPositions.clear();
        queue.clear();

        // Set initial accepted Y to the surface at the origin when starting the module
        try {
            BlockPos originTop = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, originPos);
            lastAcceptedY = originTop.getY();
        } catch (Throwable ignored) {
            lastAcceptedY = mc.player.getBlockPos().getY();
        }
        torchesPlaced = 0;

        info("GridTorch starting — origin=" + originPos.getX() + "," + originPos.getY() + "," + originPos.getZ());

        precomputeGrid();

        if (validPositions.isEmpty()) {
            info("No valid torch positions found (check settings)");
            toggle();
            return;
        }

        // Queue optimized nearest-first route so we place closer torches earlier
        if (!orderedPositions.isEmpty()) queue.addAll(orderedPositions); else queue.addAll(validPositions);

        // Submit first navigation goal
        submitNextNavGoal(primaryBaritone);
    }

    @Override
    public void onDeactivate() {
        // Stop preview if active
        previewing = false;
        previewOnlyMode = false;
        paused = false;

        try {
            Object pb = getPrimaryBaritone();
            if (pb != null && baritoneIsPathing(pb)) baritoneCancel(pb);
        } catch (Throwable ignored) {}

        precomputed.clear();
        validPositions.clear();
        skippedPositions.clear();
        queue.clear();

        currentPlacementTarget = null;
        currentNavTarget = null;
        currentTargetAgeTicks = 0;
        lastSubmissionAccepted = false;
        retryCountForCurrentTarget = 0;
        lastAcceptedY = Integer.MIN_VALUE;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null || mc.world == null) return;
        if (previewOnlyMode) return;

        // Auto-disable on dimension change or death
        if (originDim != null && !mc.world.getRegistryKey().equals(originDim)) {
            info("Dimension changed — disabling GridTorch");
            toggle();
            return;
        }

        if (mc.player.isDead()) {
            info("Player died — disabling GridTorch");
            toggle();
            return;
        }

        // When paused we preserve the computed queue/state but do not progress navigation/placement.
        if (paused) return;

        Object primaryBaritone = getPrimaryBaritone();
        if (primaryBaritone == null) {
            info("Baritone not installed — disabling GridTorch");
            toggle();
            return;
        }

        // If no active nav target, submit next
        boolean isPathing = false;
        try { isPathing = baritoneIsPathing(primaryBaritone); } catch (Throwable ignored) {}

        // Manage active navigation/arrival lifecycle
        if (currentNavTarget != null) {
            currentTargetAgeTicks++;

            BlockPos playerPos = mc.player.getBlockPos();
            boolean arrived = playerPos.equals(currentNavTarget)
                || (playerPos.getX() == currentNavTarget.getX() && playerPos.getZ() == currentNavTarget.getZ() && Math.abs(mc.player.getY() - currentNavTarget.getY()) <= 1);

            if (arrived) {
                // Attempt placement now
                currentNavTarget = null;
                currentTargetAgeTicks = 0;

                if (currentPlacementTarget != null) {
                    boolean ok = attemptPlaceTorch(currentPlacementTarget);
                    if (ok) torchesPlaced++;
                    // clear placement target regardless — success or not we continue
                    currentPlacementTarget = null;
                }

                // After placement, immediately submit next nav goal if any
                if (!queue.isEmpty()) submitNextNavGoal(primaryBaritone);
                return;
            }

            if (!isPathing && currentTargetAgeTicks >= CURRENT_TARGET_GRACE_TICKS) {
                // try one retry then skip
                if (lastSubmissionAccepted && retryCountForCurrentTarget < MAX_CURRENT_TARGET_RETRIES) {
                    // Retry via command fallback then loose goal
                    if (executeBaritoneGoto(primaryBaritone, currentNavTarget)) {
                        retryCountForCurrentTarget++;
                        lastSubmissionAccepted = true;
                        currentTargetAgeTicks = 0;
                        return;
                    }

                    if (baritoneSetLooseGoal(primaryBaritone, currentNavTarget)) {
                        retryCountForCurrentTarget++;
                        currentTargetAgeTicks = 0;
                        return;
                    }
                }

                // Skip unreachable
                info("Skipping unreachable target: " + (currentPlacementTarget == null ? currentNavTarget : currentPlacementTarget));
                currentNavTarget = null;
                currentPlacementTarget = null;
                currentTargetAgeTicks = 0;
                lastSubmissionAccepted = false;
                retryCountForCurrentTarget = 0;

                // Continue with next
                if (!queue.isEmpty()) submitNextNavGoal(primaryBaritone);
                return;
            }

            // Otherwise still en-route — wait
            return;
        }

        // If no nav target active and queue empty -> finished
        if (queue.isEmpty()) {
            info("GridTorch complete — placed " + torchesPlaced + " torches");
            toggle();
            return;
        }

        // If not pathing, ensure there's a nav goal
        if (!isPathing && currentNavTarget == null) {
            submitNextNavGoal(primaryBaritone);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        // Render when module is active OR when user started a preview
        if (!(isActive() || previewing) || !visualize.get()) return;

        // Render valid positions (green) — skip & clean up any that already have a torch in-world
        List<BlockPos> removed = new ArrayList<>();
        for (BlockPos bp : validPositions) {
            try {
                BlockState above = mc.world.getBlockState(bp.up());
                if (above.getBlock() == Blocks.TORCH || above.getBlock() == Blocks.WALL_TORCH || above.getBlock() == Blocks.SOUL_TORCH || above.getBlock() == Blocks.SOUL_WALL_TORCH) {
                    removed.add(bp);
                    continue;
                }
            } catch (Throwable ignored) {
            }

            Box b = new Box(bp);
            event.renderer.box(b, validColor.get(), validColor.get(), ShapeMode.Both, 0);
        }

        // Keep preview lists in sync by removing already-placed positions
        for (BlockPos r : removed) {
            validPositions.remove(r);
            supportNeededPositions.remove(r);
            orderedPositions.remove(r);
            queue.removeIf(p -> p.equals(r));
        }

        // Preview-only: render support-needed positions (yellow)
        if (previewing) {
            List<BlockPos> removedSupport = new ArrayList<>();
            for (BlockPos bp : supportNeededPositions) {
                try {
                    BlockState above = mc.world.getBlockState(bp.up());
                    if (above.getBlock() == Blocks.TORCH || above.getBlock() == Blocks.WALL_TORCH || above.getBlock() == Blocks.SOUL_TORCH || above.getBlock() == Blocks.SOUL_WALL_TORCH) {
                        removedSupport.add(bp);
                        continue;
                    }
                } catch (Throwable ignored) {
                }

                Box b = new Box(bp);
                event.renderer.box(b, supportColor.get(), supportColor.get(), ShapeMode.Both, 0);
            }

            for (BlockPos r : removedSupport) {
                supportNeededPositions.remove(r);
                validPositions.remove(r);
                orderedPositions.remove(r);
                queue.removeIf(p -> p.equals(r));
            }
        }

        // Render skipped positions (red)
        for (BlockPos bp : skippedPositions) {
            Box b = new Box(bp);
            event.renderer.box(b, skippedColor.get(), skippedColor.get(), ShapeMode.Both, 0);
        }
    }

    // ---------- Grid generation ----------
    /**
     * Precompute grid candidates and apply terrain filters. Publicly callable for preview mode.
     */
    public void precomputeGrid() {
        precomputed.clear();
        validPositions.clear();
        skippedPositions.clear();
        suspiciousBlocks.clear();

        // If configured, scan surrounding area (3 chunk radius) for suspicious/player-placed blocks once.
        if (avoidPlayerBlocks.get()) scanForSuspiciousBlocks(originPos, 3);

        int originX = originPos.getX();
        int originZ = originPos.getZ();

        int minX = originX - maxDistX.get();
        int maxX = originX + maxDistX.get();
        int minZ = originZ - maxDistZ.get();
        int maxZ = originZ + maxDistZ.get();

        // For each X column build an allowed set of Zs (respecting water-boundary rule relative to origin)
        Map<Integer, Set<Integer>> allowedZByX = new HashMap<>();

        // Build allowed Z sets for each X column using origin-aligned steps so the grid
        // lattice always contains the origin regardless of `maxDist` not being a multiple of spacing.
        final int sx = Math.max(1, xSpacing.get());
        final int sz = Math.max(1, zSpacing.get());
        final int maxStepsX = (int) Math.ceil(maxDistX.get() / (double) sx);

        for (int stepX = -maxStepsX; stepX <= maxStepsX; stepX++) {
            int x = originX + stepX * sx;
            if (x < minX || x > maxX) continue; // keep within configured bounds

            Set<Integer> allowedZ = new HashSet<>();

            // positive direction from originZ -> maxZ
            boolean blockedPos = false;
            for (int z = originZ; z <= maxZ; z += sz) {
                if (stopOnWater.get() && !blockedPos && isSurfaceWater(x, z)) {
                    blockedPos = true;
                    continue; // this z is water -> boundary at this z
                }

                if (!blockedPos) allowedZ.add(z);
            }

            // negative direction from originZ -> minZ
            boolean blockedNeg = false;
            for (int z = originZ; z >= minZ; z -= sz) {
                if (stopOnWater.get() && !blockedNeg && isSurfaceWater(x, z)) {
                    blockedNeg = true;
                    continue;
                }

                if (!blockedNeg) allowedZ.add(z);
            }

            allowedZByX.put(x, allowedZ);
        }

        // Generate candidates origin-first (nearest-first) and apply maxDip + terrain filters.
        // NOTE: do NOT override `lastAcceptedY` here — the caller (onActivate/startPreview) decides whether
        // to anchor dip checks. This lets preview run with no dip-anchor while active runs still enforce dip.

        // Build X/Z coordinate lists then iterate by proximity to the origin so the dip-anchor
        // (when set) is compared to nearby positions first instead of a distant corner.
        // Build origin-aligned coordinate lists so the origin column/row is always present
        final int stepsX = maxStepsX; // reuse value computed above
        final int stepsZ = (int) Math.ceil(maxDistZ.get() / (double) sz);

        List<Integer> xs = new ArrayList<>();
        for (int i = -stepsX; i <= stepsX; i++) {
            int x = originX + i * sx;
            if (x < minX || x > maxX) continue;
            xs.add(x);
        }
        xs.sort(Comparator.comparingInt(a -> Math.abs(a - originX)));

        List<Integer> zsBase = new ArrayList<>();
        for (int j = -stepsZ; j <= stepsZ; j++) {
            int z = originZ + j * sz;
            if (z < minZ || z > maxZ) continue;
            zsBase.add(z);
        }
        zsBase.sort(Comparator.comparingInt(a -> Math.abs(a - originZ)));

        for (int x : xs) {
            Set<Integer> allowedZ = allowedZByX.getOrDefault(x, Collections.emptySet());
            if (allowedZ.isEmpty()) {
                // nothing allowed in this column (water-boundary), mark as skipped for the preview/diagnostics
                for (int z : zsBase) skippedPositions.add(new BlockPos(x, lastAcceptedY, z));
                continue;
            }

            for (int z : zsBase) {
                if (!allowedZ.contains(z)) {
                    AddonTemplate.LOG.debug("GridTorch: skipping candidate x={} z={} — not allowed in column (water-boundary?)", x, z);
                    skippedPositions.add(new BlockPos(x, lastAcceptedY, z));
                    continue;
                }

                evaluateAndAddCandidate(x, z);
            }
        }

        AddonTemplate.LOG.info("GridTorch: precomputed {} total candidates, {} valid, {} skipped",
            precomputed.size(), validPositions.size(), skippedPositions.size());

        // Compute traversal order starting from the origin.
        // Option: outward spiral (configurable) or nearest-first greedy (default).
        orderedPositions.clear();
        if (spiralTraversal.get()) {
            orderedPositions.addAll(computeSpiralRoute(validPositions, originPos));
            AddonTemplate.LOG.info("GridTorch: spiral route computed ({} positions)", orderedPositions.size());
        } else {
            orderedPositions.addAll(computeOptimizedRoute(validPositions, originPos));
            AddonTemplate.LOG.info("GridTorch: optimized route computed ({} positions)", orderedPositions.size());
        }
    }

    /**
     * Recompute traversal order immediately and update the runtime queue so
     * future placements follow the current `spiralTraversal` setting.
     * Safe to call while GridTorch is active.
     */
    public void applyTraversalModeNow() {
        precomputeGrid();
        queue.clear();
        if (!orderedPositions.isEmpty()) queue.addAll(orderedPositions); else queue.addAll(validPositions);
        info("GridTorch: traversal order recomputed (spiral=" + spiralTraversal.get() + ")");
    }

    /** Pause the active module (preserve queue/state). Safe to call repeatedly. */
    public void pause() {
        if (paused) return;
        paused = true;
        try {
            Object pb = getPrimaryBaritone();
            if (pb != null) baritoneCancel(pb);
        } catch (Throwable ignored) {}
        info("GridTorch paused — use .gridtorch resume to continue");
    }

    /** Resume from a prior pause. Will submit the next nav goal if appropriate. */
    public void resume() {
        if (!paused) return;
        paused = false;
        info("GridTorch resumed");

        try {
            Object pb = getPrimaryBaritone();
            if (pb != null && currentNavTarget == null && !queue.isEmpty()) submitNextNavGoal(pb);
        } catch (Throwable ignored) {}
    }

    /**
     * Number of valid placements computed by the last precompute (total planned torches).
     */
    public int getPlannedTorchCount() {
        return validPositions.size();
    }

    /**
     * Number of placements remaining in the run queue (if the module is active).
     */
    public int getRemainingPlacements() {
        return queue.size();
    }

    private void evaluateAndAddCandidate(int x, int z) {
        try {
            BlockPos base = new BlockPos(x, 0, z);
            precomputed.add(base);

            BlockPos top = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, base);
            int groundY = top.getY();

            // If there's already a torch (including soul-torch variants) at the target position treat this as already-placed.
            try {
                BlockPos torchPos = new BlockPos(x, groundY + 1, z);
                BlockState existing = mc.world.getBlockState(torchPos);
                if (existing.getBlock() == Blocks.TORCH || existing.getBlock() == Blocks.WALL_TORCH
                    || existing.getBlock() == Blocks.SOUL_TORCH || existing.getBlock() == Blocks.SOUL_WALL_TORCH) {
                    BlockPos ground = new BlockPos(x, groundY, z);
                    validPositions.add(ground);
                    lastAcceptedY = groundY;
                    return;
                }

                // Skip if block light at torch position meets/exceeds configured threshold
                int blockLight = mc.world.getLightLevel(LightType.BLOCK, torchPos);
                if (minLightLevel.get() > 0 && blockLight >= minLightLevel.get()) {
                    AddonTemplate.LOG.debug("GridTorch: skipping {} due to block light (blockLight={} minLight={})", new BlockPos(x, groundY, z), blockLight, minLightLevel.get());
                    skippedPositions.add(new BlockPos(x, groundY, z));
                    return;
                }
            } catch (Throwable t) {
                AddonTemplate.LOG.debug("GridTorch: light-check failed for {}: {}", new BlockPos(x, groundY, z), t.toString());
                // ignore lighting/check failures and continue
            }

            // Avoid player/arty blocks if configured — reject positions within 3 chunks of any suspicious block
            if (avoidPlayerBlocks.get() && !suspiciousBlocks.isEmpty()) {
                final int chunkRadiusBlocks = 3 * 16;
                for (BlockPos sb : suspiciousBlocks) {
                    if (Math.abs(sb.getX() - x) <= chunkRadiusBlocks && Math.abs(sb.getZ() - z) <= chunkRadiusBlocks) {
                        AddonTemplate.LOG.debug("GridTorch: skipping {} due to nearby suspicious block at {}", new BlockPos(x, groundY, z), sb);
                        skippedPositions.add(new BlockPos(x, groundY, z));
                        return;
                    }
                }
            }

            // Avoid existing torches nearby (optional)
            if (avoidTorchesNearby.get()) {
                int r = avoidTorchesRadius.get();
                int r2 = r * r;

                // scan circle around the candidate (check top-of-column + 1 for torch blocks)
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz > r2) continue;

                        try {
                            BlockPos colTop = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x + dx, 0, z + dz));
                            BlockPos checkPos = colTop.up();

                            // Ignore torches that are right at the grid origin (player start) so a single torch at your feet
                            // doesn't cause the entire computed grid to be skipped when the radius is large.
                            if (originPos != null) {
                                int ox = originPos.getX();
                                int oz = originPos.getZ();
                                int oy = originPos.getY() + 1; // torch would sit above origin
                                if (Math.abs(checkPos.getX() - ox) <= 1 && Math.abs(checkPos.getZ() - oz) <= 1 && Math.abs(checkPos.getY() - oy) <= 1) continue;
                            }

                            BlockState s = mc.world.getBlockState(checkPos);
                            if (s.getBlock() == Blocks.TORCH || s.getBlock() == Blocks.WALL_TORCH || s.getBlock() == Blocks.SOUL_TORCH || s.getBlock() == Blocks.SOUL_WALL_TORCH) {
                                AddonTemplate.LOG.debug("GridTorch: skipping candidate {} because existing torch found at {} (avoidTorchesRadius={})", new Object[]{new BlockPos(x, groundY, z), checkPos, r});
                                skippedPositions.add(new BlockPos(x, groundY, z));
                                return;
                            }
                        } catch (Throwable ignored) {
                            // ignore individual-check failures and continue scanning
                        }
                    }
                }
            }

            // DIP check against last accepted
            if (lastAcceptedY != Integer.MIN_VALUE && Math.abs(groundY - lastAcceptedY) > maxDip.get()) {
                AddonTemplate.LOG.debug("GridTorch: skipping {} due to dip (groundY={} lastAcceptedY={} maxDip={})", new BlockPos(x, groundY, z), groundY, lastAcceptedY, maxDip.get());
                skippedPositions.add(new BlockPos(x, groundY, z));
                return;
            }

            // Check for immediate obstructing tree blocks above the surface
            BlockState above = mc.world.getBlockState(top.up());
            if (!breakTree.get() && (above.getBlock() instanceof PillarBlock || above.getBlock() instanceof LeavesBlock)) {
                AddonTemplate.LOG.debug("GridTorch: skipping {} due to obstructing tree/leaf block above: {}", new BlockPos(x, groundY, z), above.getBlock());
                skippedPositions.add(new BlockPos(x, groundY, z));
                return;
            }

            // Passed filters — add as valid
            BlockPos ground = new BlockPos(x, groundY, z);
            validPositions.add(ground);

            // Mark positions that will require a support block (preview-only highlight)
            BlockState groundState = mc.world.getBlockState(ground);
            if (!surfaceCanSupportTorch(ground, groundState)) supportNeededPositions.add(ground);

            lastAcceptedY = groundY;
        } catch (Throwable t) {
            // If world queries fail for some reason skip the candidate and log the error for diagnosis
            AddonTemplate.LOG.warn("GridTorch: evaluateAndAddCandidate failed for {}: {}", new BlockPos(x, mc.player.getBlockPos().getY(), z), t.toString());
            skippedPositions.add(new BlockPos(x, mc.player.getBlockPos().getY(), z));
        }
    }

    // ---------- Navigation & placement ----------
    private void submitNextNavGoal(Object primaryBaritone) {
        // poll the next placement candidate and compute a walk-to neighbor
        while (!queue.isEmpty()) {
            BlockPos ground = queue.pollFirst();
            if (ground == null) return;

            BlockPos walkTo = findAdjacentWalkPos(ground);
            if (walkTo == null) {
                // cannot reach from any adjacent position — skip
                skippedPositions.add(ground);
                continue;
            }

            // Submit Baritone goal to walkTo position
            if (!baritoneSetGoal(primaryBaritone, walkTo)) {
                // Try command fallback
                if (!executeBaritoneGoto(primaryBaritone, walkTo)) {
                    // Skip this target if Baritone refused
                    skippedPositions.add(ground);
                    continue;
                }
            }

            currentNavTarget = walkTo;
            currentPlacementTarget = ground;
            currentTargetAgeTicks = 0;
            lastSubmissionAccepted = true;
            retryCountForCurrentTarget = 0;

            return; // submitted one goal
        }
    }

    /**
     * Choose an adjacent position (N/E/S/W) with a surface player can stand on to place the torch from.
     * Preference: closest to player and within maxDip limits.
     */
    private BlockPos findAdjacentWalkPos(BlockPos ground) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        int gx = ground.getX();
        int gz = ground.getZ();

        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        for (int i = 0; i < 4; i++) {
            int nx = gx + dx[i];
            int nz = gz + dz[i];
            BlockPos base = new BlockPos(nx, 0, nz);
            BlockPos top = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, base);
            int ny = top.getY();

            // skip if dip between this neighbor and the ground exceeds maxDip
            if (Math.abs(ny - ground.getY()) > maxDip.get()) continue;

            // skip if this neighbor is underwater (respect stopOnWater as obstacle)
            if (stopOnWater.get() && isSurfaceWater(nx, nz)) continue;

            double dist = mc.player.getBlockPos().getSquaredDistance(nx, ny, nz);
            if (dist < bestDist) {
                bestDist = dist;
                best = new BlockPos(nx, ny, nz);
            }
        }

        return best;
    }

    /**
     * Attempt to place a torch on top of the provided ground block position. Returns true if placement confirmed.
     */
    private boolean attemptPlaceTorch(BlockPos ground) {
        try {
            BlockPos torchPos = ground.up();

            // Gather state/info for diagnostics
            BlockState above = mc.world.getBlockState(torchPos);
            BlockState groundState = mc.world.getBlockState(ground);
            boolean surfaceAccepts = surfaceCanSupportTorch(ground, groundState);
            AddonTemplate.LOG.debug("GridTorch: attemptPlaceTorch ground={} groundBlock={} aboveBlock={} surfaceAccepts={}", ground, groundState.getBlock(), above.getBlock(), surfaceAccepts);

            // If there's already a torch (including soul-torch variants) above the ground treat it as success — never break/replace torches.
            if (above.getBlock() == Blocks.TORCH || above.getBlock() == Blocks.WALL_TORCH
                || above.getBlock() == Blocks.SOUL_TORCH || above.getBlock() == Blocks.SOUL_WALL_TORCH) {
                clearPreviewFor(ground);
                return true;
            }

            // If there's an obstructing block above the ground, try to clear it according to settings
            if (!above.isAir()) {
                if (above.getBlock() instanceof TallPlantBlock || above.getBlock() instanceof BushBlock) {
                    if (breakGrass.get()) {
                        mc.interactionManager.attackBlock(torchPos, Direction.UP);
                    } else return false;
                } else if (above.getBlock() instanceof PillarBlock || above.getBlock() instanceof LeavesBlock) {
                    if (breakTree.get()) {
                        mc.interactionManager.attackBlock(torchPos, Direction.UP);
                    } else return false;
                } else {
                    // Generic obstruction — attempt break if allowed (do NOT attack torches — already handled above)
                    if (breakGrass.get()) mc.interactionManager.attackBlock(torchPos, Direction.UP);
                    else return false;
                }

                // Give a tick for the block to break (we're in the tick handler; it's acceptable to continue and check world state later)
            }

            // Re-check light level right before placement (in case environment changed since precompute)
            try {
                int blockLightNow = mc.world.getLightLevel(LightType.BLOCK, torchPos);
                if (minLightLevel.get() > 0 && blockLightNow >= minLightLevel.get()) {
                    AddonTemplate.LOG.info("GridTorch: skipping placement at {} because block light (now) = {} >= minLightLevel={}", torchPos, blockLightNow, minLightLevel.get());
                    return false;
                }
            } catch (Throwable ignored) {
            }

            // Ensure we have a torch in hotbar
            int torchSlot = findItemInHotbar(Items.TORCH);
            if (torchSlot == -1) {
                info("Out of torches — pausing GridTorch (use .gridtorch resume after refilling)");
                pause();
                return false;
            }


            // Try placing the torch directly first (preferred)
            meteordevelopment.meteorclient.utils.player.InvUtils.swap(torchSlot, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(ground), Direction.UP, ground, false));

            // Check result immediately — prefer a standing torch; accept wall torch only as fallback
            BlockState placed = mc.world.getBlockState(torchPos);
            if (placed.getBlock() == Blocks.TORCH || placed.getBlock() == Blocks.SOUL_TORCH) {
                clearPreviewFor(ground);
                return true;
            }

            // Direct placement did not produce a standing torch. Try wall-torch fallback before other fallbacks.
            if (tryPlaceWallTorch(torchPos, torchSlot)) {
                AddonTemplate.LOG.info("GridTorch: placed wall-torch fallback at {}", torchPos);
                clearPreviewFor(ground);
                return true;
            }

            // If a wall-torch was placed by the direct attempt (edge cases), accept it.
            if (placed.getBlock() == Blocks.WALL_TORCH || placed.getBlock() == Blocks.SOUL_WALL_TORCH) {
                clearPreviewFor(ground);
                return true;
            }

            // Direct placement failed. If the ground *can* accept a torch we should NOT place a support block — attempt one retry then skip.
            if (surfaceAccepts) {
                AddonTemplate.LOG.info("GridTorch: direct torch placement failed at {} but surface reports legal — retrying placement once", ground);

                // Retry a single time (handles transient placement misses)
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                    new BlockHitResult(Vec3d.ofCenter(ground), Direction.UP, ground, false));

                placed = mc.world.getBlockState(torchPos);
                if (placed.getBlock() == Blocks.TORCH || placed.getBlock() == Blocks.SOUL_TORCH) {
                    clearPreviewFor(ground);
                    return true;
                }

                // Try wall-torch fallback on retry
                if (tryPlaceWallTorch(torchPos, torchSlot)) {
                    AddonTemplate.LOG.info("GridTorch: placed wall-torch fallback on retry at {}", torchPos);
                    clearPreviewFor(ground);
                    return true;
                }

                if (placed.getBlock() == Blocks.WALL_TORCH || placed.getBlock() == Blocks.SOUL_WALL_TORCH) {
                    clearPreviewFor(ground);
                    return true;
                }

                AddonTemplate.LOG.info("GridTorch: retry also failed at {} — skipping support placement", ground);
                return false;
            }

            // Only place a support block if surface cannot accept a torch and user allows it
            if (!makeBlockLegal.get()) return false;

            int supportSlot = findItemInHotbar(Items.DIRT);
            if (supportSlot == -1) supportSlot = findItemInHotbar(Items.COBBLESTONE);
            if (supportSlot == -1) return false; // cannot make legal

            // Ensure the torch position above ground is still air before we attempt any placement
            if (!mc.world.getBlockState(torchPos).isAir()) {
                AddonTemplate.LOG.info("GridTorch: expected {} to be air before placing support but found {} — skipping support placement", torchPos, mc.world.getBlockState(torchPos).getBlock());
                return false;
            }

            // Verify the ground block can be replaced (we will not overwrite non-replaceable player blocks or existing torches)
            BlockState groundStateNow = mc.world.getBlockState(ground);
            if (groundStateNow.getBlock() == Blocks.TORCH || groundStateNow.getBlock() == Blocks.WALL_TORCH
                || groundStateNow.getBlock() == Blocks.SOUL_TORCH || groundStateNow.getBlock() == Blocks.SOUL_WALL_TORCH) {
                // a torch already exists on the ground — treat as placed
                clearPreviewFor(ground);
                return true;
            }

            boolean groundReplaceable = groundStateNow.isAir()
                || groundStateNow.getBlock() == Blocks.WATER
                || groundStateNow.getBlock() instanceof TallPlantBlock
                || groundStateNow.getBlock() instanceof BushBlock;

            if (!groundReplaceable) {
                AddonTemplate.LOG.info("GridTorch: cannot place support at {} because block {} is not replaceable — skipping", ground, groundStateNow.getBlock());
                return false;
            }

            AddonTemplate.LOG.info("GridTorch: placing support block at {} (making surface torch-legal)", ground);

            // Place support block at the ground position (under the torch) BEFORE placing the torch
            meteordevelopment.meteorclient.utils.player.InvUtils.swap(supportSlot, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(ground), Direction.UP, ground, false));

            // Verify a block was placed at ground
            if (mc.world.getBlockState(ground).isAir()) return false;

            // Place torch on top of the newly-placed support block (target the ground position)
            meteordevelopment.meteorclient.utils.player.InvUtils.swap(torchSlot, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(ground), Direction.UP, ground, false));

            placed = mc.world.getBlockState(torchPos);
            boolean success = (placed.getBlock() == Blocks.TORCH || placed.getBlock() == Blocks.WALL_TORCH || placed.getBlock() == Blocks.SOUL_TORCH || placed.getBlock() == Blocks.SOUL_WALL_TORCH);
            if (success) clearPreviewFor(ground);
            return success;
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("GridTorch: failed to place torch at {}: {}", ground, t.toString());
            return false;
        }
    }

    private boolean surfaceCanSupportTorch(BlockPos pos, BlockState state) {
        try {
            // Accurate check using the actual block position's top face
            return state.isSideSolidFullSquare(mc.world, pos, Direction.UP);
        } catch (Throwable ignored) {
            // Fallback: treat obviously non-air blocks as legal (best-effort)
            return !state.isAir();
        }
    }

    /** Remove preview/route entries for a block that has been (or otherwise) placed. */
    private void clearPreviewFor(BlockPos ground) {
        try {
            validPositions.remove(ground);
            supportNeededPositions.remove(ground);
            skippedPositions.remove(ground);
            precomputed.remove(ground);
            orderedPositions.remove(ground);
            // remove matching entries from runtime queue
            queue.removeIf(p -> p.equals(ground));
        } catch (Throwable ignored) {
        }
    }

    private int findItemInHotbar(net.minecraft.item.Item item) {
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).getItem() == item) return i;
        }
        return -1;
    }

    /**
     * Try to place a wall-torch at the given air position by clicking the face of an adjacent solid block.
     * Returns true if a wall-torch (normal or soul) was successfully placed.
     */
    private boolean tryPlaceWallTorch(BlockPos torchPos, int torchSlot) {
        if (torchSlot == -1) return false;

        // Ensure the target is still air
        if (!mc.world.getBlockState(torchPos).isAir()) return false;

        Direction[] dirs = new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
        for (Direction d : dirs) {
            BlockPos adj = torchPos.offset(d);
            BlockState s = mc.world.getBlockState(adj);

            // Block must present a solid face facing the torch position
            if (!s.isSideSolidFullSquare(mc.world, adj, d.getOpposite())) continue;

            // Attempt to place a wall-torch by clicking the adjacent block face
            meteordevelopment.meteorclient.utils.player.InvUtils.swap(torchSlot, false);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(adj), d.getOpposite(), adj, false));

            BlockState placed = mc.world.getBlockState(torchPos);
            if (placed.getBlock() == Blocks.WALL_TORCH || placed.getBlock() == Blocks.SOUL_WALL_TORCH) return true;
        }

        return false;
    }

    // ---------- Baritone reflection helpers (copied/adjusted from SquareSpiral) ----------
    private Object getBaritoneProvider() {
        try {
            Class<?> api = Class.forName("baritone.api.BaritoneAPI");
            return api.getMethod("getProvider").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object getPrimaryBaritone() {
        try {
            Object provider = getBaritoneProvider();
            if (provider == null) return null;
            return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean baritoneIsPathing(Object primaryBaritone) {
        try {
            Object pc = primaryBaritone.getClass().getMethod("getPathingControl").invoke(primaryBaritone);
            return (Boolean) pc.getClass().getMethod("isPathing").invoke(pc);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void baritoneCancel(Object primaryBaritone) {
        try {
            Object pc = primaryBaritone.getClass().getMethod("getPathingControl").invoke(primaryBaritone);
            pc.getClass().getMethod("cancelEverything").invoke(pc);
        } catch (Throwable ignored) {
        }
    }

    private boolean baritoneSetGoal(Object primaryBaritone, BlockPos target) {
        try {
            Object goal = null;

            try {
                Class<?> goalXZ = Class.forName("baritone.api.pathing.goals.GoalXZ");
                try {
                    goal = goalXZ.getConstructor(int.class, int.class).newInstance(target.getX(), target.getZ());
                } catch (NoSuchMethodException ignored) {
                    try {
                        goal = goalXZ.getConstructor(double.class, double.class).newInstance((double) target.getX(), (double) target.getZ());
                    } catch (NoSuchMethodException ignored2) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }

            if (goal == null) {
                try {
                    Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                    try {
                        goal = goalNear.getConstructor(int.class, int.class, int.class, int.class)
                            .newInstance(target.getX(), target.getY(), target.getZ(), 1);
                    } catch (NoSuchMethodException ignored) {
                        try {
                            goal = goalNear.getConstructor(double.class, double.class, double.class, double.class)
                                .newInstance((double) target.getX(), (double) target.getY(), (double) target.getZ(), 1d);
                        } catch (NoSuchMethodException ignored2) {
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (goal == null) {
                Class<?> goalBlockCls = Class.forName("baritone.api.pathing.goals.GoalBlock");
                try {
                    goal = goalBlockCls.getConstructor(BlockPos.class).newInstance(target);
                } catch (NoSuchMethodException ignored) {
                }
                if (goal == null) {
                    try {
                        goal = goalBlockCls.getConstructor(int.class, int.class, int.class)
                            .newInstance(target.getX(), target.getY(), target.getZ());
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }

            if (goal != null) {
                Object customGoalProcess = primaryBaritone.getClass().getMethod("getCustomGoalProcess").invoke(primaryBaritone);
                for (java.lang.reflect.Method m : customGoalProcess.getClass().getMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 0) continue;
                    if (!params[0].isAssignableFrom(goal.getClass()) && !params[0].isInstance(goal)) continue;

                    Object[] args = new Object[params.length];
                    args[0] = goal;
                    for (int i = 1; i < params.length; i++) {
                        Class<?> p = params[i];
                        if (p.isPrimitive()) {
                            if (p == boolean.class) args[i] = false;
                            else if (p == byte.class) args[i] = (byte) 0;
                            else if (p == short.class) args[i] = (short) 0;
                            else if (p == int.class) args[i] = 0;
                            else if (p == long.class) args[i] = 0L;
                            else if (p == float.class) args[i] = 0f;
                            else if (p == double.class) args[i] = 0d;
                            else if (p == char.class) args[i] = '\0';
                            else args[i] = 0;
                        } else args[i] = null;
                    }

                    try {
                        m.invoke(customGoalProcess, args);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }

                for (java.lang.reflect.Method m : primaryBaritone.getClass().getMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 0) continue;
                    if (!params[0].isAssignableFrom(goal.getClass()) && !params[0].isInstance(goal)) continue;

                    Object[] args = new Object[params.length];
                    args[0] = goal;
                    for (int i = 1; i < params.length; i++) args[i] = params[i].isPrimitive() ? 0 : null;

                    try {
                        m.invoke(primaryBaritone, args);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }

                throw new IllegalStateException("No compatible Baritone method found to accept Goal");
            }

            return false;
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("GridTorch: failed to set Baritone goal to {}: {}", target, t.toString());
            return false;
        }
    }

    private boolean baritoneSetLooseGoal(Object primaryBaritone, BlockPos target) {
        try {
            Object goal = null;
            try {
                Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                try {
                    goal = goalNear.getConstructor(int.class, int.class, int.class, int.class)
                        .newInstance(target.getX(), target.getY(), target.getZ(), 2);
                } catch (NoSuchMethodException ignored) {
                    try {
                        goal = goalNear.getConstructor(double.class, double.class, double.class, double.class)
                            .newInstance((double) target.getX(), (double) target.getY(), (double) target.getZ(), 2d);
                    } catch (NoSuchMethodException ignored2) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }

            if (goal == null) return executeBaritoneGoto(primaryBaritone, target);

            Object customGoalProcess = primaryBaritone.getClass().getMethod("getCustomGoalProcess").invoke(primaryBaritone);
            for (java.lang.reflect.Method m : customGoalProcess.getClass().getMethods()) {
                if (!m.getName().equals("setGoalAndPath")) continue;
                if (m.getParameterCount() != 1) continue;

                try {
                    m.invoke(customGoalProcess, goal);
                    return true;
                } catch (Throwable ignored) {
                }
            }

            return false;
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("GridTorch: loose goal submission failed for {}: {}", target, t.toString());
            return false;
        }
    }

    private boolean executeBaritoneCommand(Object primaryBaritone, String cmd) {
        try {
            try {
                Object cm = primaryBaritone.getClass().getMethod("getCommandManager").invoke(primaryBaritone);
                if (cm != null) {
                    try {
                        java.lang.reflect.Method exec = cm.getClass().getMethod("execute", String.class);
                        exec.invoke(cm, cmd);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }

            try {
                Object provider = getBaritoneProvider();
                if (provider != null) {
                    Object cm2 = provider.getClass().getMethod("getCommandManager").invoke(provider);
                    if (cm2 != null) {
                        try {
                            java.lang.reflect.Method exec2 = cm2.getClass().getMethod("execute", String.class);
                            exec2.invoke(cm2, cmd);
                            return true;
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            for (java.lang.reflect.Method m : primaryBaritone.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                String name = m.getName().toLowerCase();
                if (!name.contains("execute") && !name.contains("command") && !name.contains("run")) continue;

                try {
                    m.invoke(primaryBaritone, cmd);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("GridTorch: executeBaritoneCommand failed for cmd='{}': {}", cmd, t.toString());
        }

        return false;
    }

    private boolean executeBaritoneGoto(Object primaryBaritone, BlockPos target) {
        String cmd = "goto " + target.getX() + " " + target.getY() + " " + target.getZ();
        return executeBaritoneCommand(primaryBaritone, cmd);
    }

    // ---------- Preview control ----------
    /**
     * Start a visual-only preview from the player's current block position.
     * Does not start navigation or place torches.
     */
    public void startPreview() {
        if (mc.player == null || mc.world == null) return;
        if (isActive()) {
            info("Module is active — disable it before starting a preview");
            return;
        }

        // Enable the module in preview-only mode so render handlers are active but navigation/placement is suppressed.
        previewOnlyMode = true;
        toggle(); // onActivate will handle precomputeGrid() in preview-only path
    }

    /**
     * Stop an active preview and clear preview data.
     */
    public void stopPreview() {
        // If we enabled the module solely for preview, disable it now.
        if (previewOnlyMode && isActive()) {
            previewOnlyMode = false;
            toggle(); // onDeactivate will clear preview state
            return;
        }

        previewing = false;
        precomputed.clear();
        validPositions.clear();
        skippedPositions.clear();
        info("GridTorch preview stopped");
    }

    /**
     * Cancel any active Baritone pathing immediately.
     * Safe to call whether or not the module is active.
     */
    public void cancelBaritonePath() {
        try {
            Object pb = getPrimaryBaritone();
            if (pb != null) baritoneCancel(pb);
        } catch (Throwable ignored) {}
    }

    // ---------- Configuration helpers ----------

    /**
     * Reset all user-facing settings back to their defaults.
     * Includes visual colors and traversal/tolerance options.
     */
    public void resetSettings() {
        xSpacing.set(8);
        zSpacing.set(8);
        maxDistX.set(128);
        maxDistZ.set(128);
        spiralTraversal.set(false);

        stopOnWater.set(true);
        maxDip.set(5);
        minLightLevel.set(8);
        avoidPlayerBlocks.set(false);
        avoidTorchesNearby.set(false);
        avoidTorchesRadius.set(12);

        breakGrass.set(true);
        breakTree.set(true);
        makeBlockLegal.set(true);

        visualize.set(true);

        try {
            validColor.set(new SettingColor(new java.awt.Color(0, 255, 0)));
            skippedColor.set(new SettingColor(new java.awt.Color(255, 0, 0)));
            supportColor.set(new SettingColor(new java.awt.Color(255, 255, 0)));
        } catch (Throwable ignored) {
            // best-effort for color resets; ignore if API differs at runtime
        }
    }

    // ---------- Route optimization ----------
    private List<BlockPos> computeOptimizedRoute(List<BlockPos> points, BlockPos start) {
        List<BlockPos> route = new ArrayList<>();
        if (points == null || points.isEmpty()) return route;

        List<BlockPos> remaining = new ArrayList<>(points);
        BlockPos cursor = start == null ? mc.player.getBlockPos() : start;

        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            long bestDist = Long.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                BlockPos p = remaining.get(i);
                long dx = cursor.getX() - p.getX();
                long dy = cursor.getY() - p.getY();
                long dz = cursor.getZ() - p.getZ();
                long dist = dx * dx + dy * dy + dz * dz;

                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }

            BlockPos next = remaining.remove(bestIdx);
            route.add(next);
            cursor = next;
        }

        return route;
    }

    /**
     * Compute an outward square-spiral route of the provided points starting from the origin.
     * The spiral advances in grid steps based on `xSpacing` / `zSpacing` and only includes
     * points that exist in the `points` list. This guarantees a visually outward spiral
     * traversal when `spiralTraversal` is enabled.
     */
    private List<BlockPos> computeSpiralRoute(List<BlockPos> points, BlockPos start) {
        List<BlockPos> route = new ArrayList<>();
        if (points == null || points.isEmpty()) return route;

        // Build quick lookup by X,Z coordinate
        Map<Long, BlockPos> lookup = new HashMap<>();
        for (BlockPos p : points) {
            long key = (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
            lookup.put(key, p);
        }

        final int originX = start == null ? mc.player.getBlockPos().getX() : start.getX();
        final int originZ = start == null ? mc.player.getBlockPos().getZ() : start.getZ();
        final int sx = Math.max(1, xSpacing.get());
        final int sz = Math.max(1, zSpacing.get());

        final int maxStepsX = (int) Math.ceil(maxDistX.get() / (double) sx);
        final int maxStepsZ = (int) Math.ceil(maxDistZ.get() / (double) sz);

        final int totalPossible = (2 * maxStepsX + 1) * (2 * maxStepsZ + 1);

        // Helper to check and add a world coord if present in lookup
        Set<Long> added = new HashSet<>();
        BiConsumer<Integer,Integer> tryAdd = (ox, oz) -> {
            int wx = originX + ox * sx;
            int wz = originZ + oz * sz;
            long k = (((long) wx) << 32) ^ (wz & 0xffffffffL);
            if (!added.contains(k) && lookup.containsKey(k)) {
                route.add(lookup.get(k));
                added.add(k);
            }
        };

        // Add origin if present
        tryAdd.accept(0, 0);

        // Spiral generator (right, up, left, down), increasing leg length
        int cx = 0, cz = 0;
        int stepLen = 1;
        int dir = 0;
        int[] ddx = {1, 0, -1, 0};
        int[] ddz = {0, 1, 0, -1};

        int stepsGenerated = 1; // origin counted
        int maxGenerate = Math.max(totalPossible, points.size() * 4); // cap to reasonable bound

        while (stepsGenerated < maxGenerate && route.size() < points.size()) {
            for (int side = 0; side < 2; side++) {
                for (int s = 0; s < stepLen; s++) {
                    cx += ddx[dir];
                    cz += ddz[dir];
                    stepsGenerated++;

                    // only consider coordinates within configured bounds
                    if (Math.abs(cx) <= maxStepsX && Math.abs(cz) <= maxStepsZ) tryAdd.accept(cx, cz);

                    if (route.size() >= points.size()) break;
                }

                dir = (dir + 1) & 3;
                if (route.size() >= points.size()) break;
            }

            stepLen++;

            // safety: if we've generated a very large number of steps with no additions, stop
            if (stepsGenerated > totalPossible * 2) break;
        }

        // If anything remains (due to alignment differences), append remaining points deterministically
        if (route.size() < points.size()) {
            Set<BlockPos> present = new HashSet<>(route);
            for (BlockPos p : points) if (!present.contains(p)) route.add(p);
        }

        return route;
    }

    // ---------- Small helpers ----------

    /** Scan nearby area (chunkRadius in chunks) and collect suspicious/player-made blocks. */
    private void scanForSuspiciousBlocks(BlockPos center, int chunkRadius) {
        suspiciousBlocks.clear();
        if (mc.world == null || center == null) return;

        final int r = chunkRadius * 16;
        final int minX = center.getX() - r;
        final int maxX = center.getX() + r;
        final int minZ = center.getZ() - r;
        final int maxZ = center.getZ() + r;

        try {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos top = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                    int topY = top.getY();

                    // Check a small vertical column near the surface for suspicious blocks
                    for (int dy = -2; dy <= 5; dy++) {
                        int y = topY + dy;
                        if (y < mc.world.getBottomY()) continue;
                        BlockPos p = new BlockPos(x, y, z);
                        BlockState s = mc.world.getBlockState(p);
                        if (s.isAir()) continue;

                        if (isSuspiciousBlock(s)) {
                            suspiciousBlocks.add(p);
                            break; // found suspicious block for this column
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            AddonTemplate.LOG.warn("GridTorch: suspicious-block scan failed: {}", ignored.toString());
        }

        AddonTemplate.LOG.info("GridTorch: suspicious blocks found={} (scanRadiusChunks={})", suspiciousBlocks.size(), chunkRadius);
    }

    private boolean isSuspiciousBlock(BlockState state) {
        if (state == null) return false;
        net.minecraft.block.Block b = state.getBlock();

        // Direct block checks (common player-built / redstone / storage / utility blocks)
        if (b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER) return true;
        if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.BARREL) return true;
        if (b == Blocks.BEACON || b == Blocks.ENCHANTING_TABLE || b == Blocks.HOPPER) return true;
        if (b == Blocks.LANTERN) return true;

        // Redstone / automation
        if (b == Blocks.REDSTONE_WIRE || b == Blocks.REDSTONE_TORCH || b == Blocks.REPEATER || b == Blocks.COMPARATOR) return true;
        if (b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.OBSERVER || b == Blocks.DISPENSER || b == Blocks.DROPPER) return true;
        if (b == Blocks.LEVER) return true;

        // Storage/misc
        if (b instanceof ShulkerBoxBlock || b instanceof ChestBlock || b instanceof BarrelBlock) return true;
        if (b instanceof DoorBlock || b instanceof SignBlock || b instanceof HopperBlock) return true;
        if (b == Blocks.BEACON || b == Blocks.ENCHANTING_TABLE) return true;

        // Heuristic by translation key for planks/concrete/terracotta/brick/stone_brick
        try {
            String key = b.getTranslationKey(); // e.g. block.minecraft.oak_planks
            if (key.contains("plank") || key.contains("concrete") || key.contains("terracotta") || key.contains("brick") || key.contains("stone_brick")) return true;
        } catch (Throwable ignored) {
        }

        return false;
    }

    private boolean isSurfaceWater(int x, int z) {
        try {
            BlockPos top = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            BlockState above = mc.world.getBlockState(top.up());
            BlockState atTop = mc.world.getBlockState(top);
            if (above.getBlock() == Blocks.WATER || atTop.getBlock() == Blocks.WATER) return true;
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
