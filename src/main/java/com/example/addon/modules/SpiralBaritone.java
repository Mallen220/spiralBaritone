package com.example.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class SpiralBaritone extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Radius in blocks to fully cover.")
        .defaultValue(5)
        .min(1)
        .max(512)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> returnToStart = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-start")
        .description("Return to original position after spiral completes.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disable module when spiral completes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> useRelativeGoals = sgGeneral.add(new BoolSetting.Builder()
        .name("use-relative-goals")
        .description("Use relative coordinates instead of absolute.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> delayBetweenGoals = sgGeneral.add(new IntSetting.Builder()
        .name("delay-between-goals")
        .description("Delay in ticks between goal submissions.")
        .defaultValue(0)
        .min(0)
        .max(100)
        .build()
    );

    private final Setting<Integer> placeTorchEveryN = sgGeneral.add(new IntSetting.Builder()
        .name("place-torch-every-n-blocks")
        .description("Place a torch every N blocks (0 to disable).")
        .defaultValue(0)
        .min(0)
        .max(64)
        .build()
    );

    private BlockPos startPos;
    private List<BlockPos> spiralPath;
    private int currentGoalIndex;
    private int tickCounter;
    private boolean spiralComplete;
    private boolean returningToStart;
    private String lastDimension;

    public SpiralBaritone() {
        super(AddonTemplate.CATEGORY, "spiral-baritone", "Automatically walks in a square spiral pattern using Baritone.");
    }

    @Override
    public void onActivate() {
        // Check if Baritone is available
        try {
            if (BaritoneAPI.getProvider() == null) {
                error("Baritone is not installed!");
                toggle();
                return;
            }
        } catch (Exception e) {
            error("Baritone is not installed!");
            toggle();
            return;
        }

        if (mc.player == null) {
            error("Player is null!");
            toggle();
            return;
        }

        // Cancel any existing Baritone path
        try {
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
        } catch (Exception e) {
            warning("Could not cancel existing Baritone path.");
        }

        // Store starting position
        startPos = mc.player.getBlockPos();
        lastDimension = getDimensionId();

        // Generate spiral path
        spiralPath = generateSpiralPath(startPos, radius.get());
        currentGoalIndex = 0;
        tickCounter = 0;
        spiralComplete = false;
        returningToStart = false;

        info("Starting spiral from " + startPos.toShortString() + " with radius " + radius.get());
        info("Generated " + spiralPath.size() + " goals");

        // Queue first goal
        if (!spiralPath.isEmpty()) {
            queueNextGoal();
        }
    }

    @Override
    public void onDeactivate() {
        // Cancel Baritone pathing
        try {
            if (BaritoneAPI.getProvider() != null) {
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
            }
        } catch (Exception e) {
            // Baritone not available
        }

        spiralPath = null;
        startPos = null;
        currentGoalIndex = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;

        // Check for dimension change
        String currentDimension = getDimensionId();
        if (!currentDimension.equals(lastDimension)) {
            warning("Dimension changed! Auto-disabling.");
            toggle();
            return;
        }

        // Handle delay between goals
        if (delayBetweenGoals.get() > 0) {
            tickCounter++;
            if (tickCounter < delayBetweenGoals.get()) {
                return;
            }
            tickCounter = 0;
        }

        try {
            // Check if Baritone is still pathing
            boolean isPathing = BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing();

            if (!isPathing && !spiralComplete) {
                // Baritone finished current goal, queue next
                if (currentGoalIndex < spiralPath.size()) {
                    queueNextGoal();
                } else {
                    // Spiral complete
                    spiralComplete = true;
                    info("Spiral complete!");

                    if (returnToStart.get() && !returningToStart) {
                        returningToStart = true;
                        info("Returning to start position...");
                        setBaritoneGoal(startPos);
                    } else {
                        if (autoDisable.get()) {
                            info("Auto-disabling...");
                            toggle();
                        }
                    }
                }
            } else if (!isPathing && returningToStart) {
                // Returned to start
                info("Returned to start position!");
                if (autoDisable.get()) {
                    toggle();
                }
            }
        } catch (Exception e) {
            error("Error in spiral logic: " + e.getMessage());
            toggle();
        }
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) {
            toggle();
        }
    }

    private void queueNextGoal() {
        if (currentGoalIndex >= spiralPath.size()) return;

        BlockPos goal = spiralPath.get(currentGoalIndex);
        currentGoalIndex++;

        setBaritoneGoal(goal);

        // Optional: Place torch logic (simplified - would need inventory management)
        if (placeTorchEveryN.get() > 0 && currentGoalIndex % placeTorchEveryN.get() == 0) {
            // Note: Actual torch placement would require additional logic
            // This is a placeholder for the optional enhancement
        }
    }

    private void setBaritoneGoal(BlockPos pos) {
        try {
            GoalBlock goal = new GoalBlock(pos);
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
        } catch (Exception e) {
            error("Failed to set Baritone goal: " + e.getMessage());
        }
    }

    private List<BlockPos> generateSpiralPath(BlockPos center, int rad) {
        List<BlockPos> path = new ArrayList<>();

        int x = 0;
        int z = 0;

        // Directions: 0 = +X, 1 = +Z, 2 = -X, 3 = -Z
        int direction = 0;
        int steps = 1;

        // Add center position
        path.add(new BlockPos(center.getX() + x, center.getY(), center.getZ() + z));

        // Generate spiral - continue until we cover the full square area
        while (steps <= 2 * rad + 1) {
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < steps; j++) {
                    // Move in current direction
                    switch (direction) {
                        case 0: x++; break; // +X
                        case 1: z++; break; // +Z
                        case 2: x--; break; // -X
                        case 3: z--; break; // -Z
                    }

                    // Check if within radius
                    if (Math.max(Math.abs(x), Math.abs(z)) <= rad) {
                        BlockPos pos;
                        if (useRelativeGoals.get()) {
                            pos = new BlockPos(x, 0, z);
                        } else {
                            pos = new BlockPos(center.getX() + x, center.getY(), center.getZ() + z);
                        }
                        path.add(pos);
                    }
                }

                // Rotate direction clockwise
                direction = (direction + 1) % 4;
            }

            steps++;
        }

        return path;
    }

    private String getDimensionId() {
        if (mc.world == null) return "unknown";
        return mc.world.getRegistryKey().getValue().toString();
    }

    private void info(String message) {
        ChatUtils.info("(SpiralBaritone)", message);
    }

    private void warning(String message) {
        ChatUtils.warning("(SpiralBaritone)", message);
    }

    private void error(String message) {
        ChatUtils.error("(SpiralBaritone)", message);
    }
}
