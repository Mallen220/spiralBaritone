package com.example.addon.modules;

// Access Baritone reflectively (Baritone is optional at compile-time/runtime).
// We avoid direct baritone imports so the addon compiles when Baritone isn't present.

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Square spiral movement module — submits Baritone goals in a square-spiral order so the player walks and covers
 * every block inside the square defined by max(|x - startX|, |z - startZ|) <= radius.
 *
 * Behavior summary:
 * - Captures start position when enabled
 * - Generates spiral offsets (clockwise: +X, +Z, -X, -Z with steps 1,1,2,2,3,3...)
 * - Submits Baritone GoalBlock for each spiral position in order
 * - Optionally returns to start and/or auto-disables when finished
 *
 * Notes:
 * - Integrates directly with Baritone API (no chat commands)
 * - If Baritone is missing the module will disable itself and inform the user
 */
public class SquareSpiral extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Radius in blocks to fully cover")
        .defaultValue(5)
        .min(1)
        .max(512)
        .sliderMax(64)
        .build()
    );

    public final Setting<Boolean> returnToStart = sgGeneral.add(new BoolSetting.Builder()
        .name("return-to-start")
        .description("If true, player returns to original position after spiral completes")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> autoDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-disable")
        .description("Disables module when spiral completes")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> useRelativeGoals = sgGeneral.add(new BoolSetting.Builder()
        .name("use-relative-goals")
        .description("If true: goals are calculated relative to the player's current position; otherwise anchored to the start position")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> delayBetweenGoals = sgGeneral.add(new IntSetting.Builder()
        .name("delay-between-goals")
        .description("Delay (in ticks) between submitting consecutive Baritone goals")
        .defaultValue(0)
        .min(0)
        .max(200)
        .build()
    );

    // Optional bonus setting — placement behavior is not intrusive and is left intentionally simple (see TODO)
    private final Setting<Integer> placeTorchEveryN = sgGeneral.add(new IntSetting.Builder()
        .name("place-torch-every-n-blocks")
        .description("Optional: place a torch every N goals (0 = disabled).\nNOTE: basic placeholder — full automatic placement may require player inventory/tool checks and is intentionally non-blocking")
        .defaultValue(0)
        .min(0)
        .max(64)
        .build()
    );

    // Internal state
    private final Deque<BlockPos> offsets = new ArrayDeque<>(); // offsets relative to start (x, 0, z)
    private BlockPos startPos;
    private RegistryKey<World> startDim;

    private boolean returningToStart = false;
    private int delayTicker = 0;
    private int goalsSubmitted = 0;

    // Current active goal (absolute world BlockPos). We wait until the player reaches this position
    // before submitting the next spiral goal.
    private BlockPos currentTarget = null;
    // How many ticks have passed since the currentTarget was submitted. Used to allow a short grace period
    // for Baritone to start pathing before we decide a target is unreachable.
    private int currentTargetAgeTicks = 0;
    private static final int CURRENT_TARGET_GRACE_TICKS = 40; // ~2s grace to avoid premature skips

    // Tracks whether the last submission was accepted (reflection succeeded) and retry state
    private boolean lastSubmissionAccepted = false;
    private int retryCountForCurrentTarget = 0;
    private static final int MAX_CURRENT_TARGET_RETRIES = 1;

    // Rate-limited chat/log spam guard for repeated skip events
    private int skipStreak = 0;

    // Last successful Goal class name (for diagnostic logging)
    private String lastSubmittedGoalClassName = null;
    // --- Reflection helpers for Baritone (Baritone is optional at runtime) ---
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

            // Prefer column-based goals so Baritone doesn't reject reachable XZ positions due to Y mismatch.
            try {
                Class<?> goalXZ = Class.forName("baritone.api.pathing.goals.GoalXZ");
                try {
                    goal = goalXZ.getConstructor(int.class, int.class).newInstance(target.getX(), target.getZ());
                } catch (NoSuchMethodException ignored) {
                    try {
                        goal = goalXZ.getConstructor(double.class, double.class).newInstance((double)target.getX(), (double)target.getZ());
                    } catch (NoSuchMethodException ignored2) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }

            // Try GoalNear (allows small range) if GoalXZ isn't available
            if (goal == null) {
                try {
                    Class<?> goalNear = Class.forName("baritone.api.pathing.goals.GoalNear");
                    try {
                        // (int x, int y, int z, int range)
                        goal = goalNear.getConstructor(int.class, int.class, int.class, int.class)
                            .newInstance(target.getX(), target.getY(), target.getZ(), 1);
                    } catch (NoSuchMethodException ignored) {
                        try {
                            // (double x, double y, double z, double range)
                            goal = goalNear.getConstructor(double.class, double.class, double.class, double.class)
                                .newInstance((double)target.getX(), (double)target.getY(), (double)target.getZ(), 1d);
                        } catch (NoSuchMethodException ignored2) {
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }

            // Fallback to GoalBlock if none of the above are available
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

                if (goal == null) throw new NoSuchMethodException("No suitable Goal constructor found");
            }

            // Record which Goal implementation we constructed (diagnostic)
            if (goal != null) lastSubmittedGoalClassName = goal.getClass().getSimpleName();

            Object customGoalProcess = primaryBaritone.getClass().getMethod("getCustomGoalProcess").invoke(primaryBaritone);

            // Try invoking any method on the custom goal process that accepts the Goal object as the first parameter.
            // For overloads with extra parameters, provide safe defaults (false/0/null).
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
                        else if (p == byte.class) args[i] = (byte)0;
                        else if (p == short.class) args[i] = (short)0;
                        else if (p == int.class) args[i] = 0;
                        else if (p == long.class) args[i] = 0L;
                        else if (p == float.class) args[i] = 0f;
                        else if (p == double.class) args[i] = 0d;
                        else if (p == char.class) args[i] = '\0';
                        else args[i] = 0; // fallback
                    } else {
                        args[i] = null;
                    }
                }

                try {
                    m.invoke(customGoalProcess, args);
                    return true;
                } catch (Throwable ignored) {
                    // try next compatible method
                }
            }

            // If that didn't work, try methods on the primaryBaritone object itself (some API variants expose helpers there)
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
        } catch (Throwable t) {
            AddonTemplate.LOG.error("SquareSpiral: failed to set Baritone goal to {}: {}", target, t.toString());
            info("Baritone goal submission failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Attempt a "loose" submission for a target (GoalNear with a small radius) — used as a retry when Baritone
     * initially doesn't start pathing for a submitted goal.
     */
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
                            .newInstance((double)target.getX(), (double)target.getY(), (double)target.getZ(), 2d);
                    } catch (NoSuchMethodException ignored2) {
                    }
                }
            } catch (ClassNotFoundException ignored) {
            }

            if (goal == null) {
                // For now use the command fallback instead of API fallback
                return executeBaritoneGoto(primaryBaritone, target);
            }

            lastSubmittedGoalClassName = goal.getClass().getSimpleName();

            Object customGoalProcess = primaryBaritone.getClass().getMethod("getCustomGoalProcess").invoke(primaryBaritone);

            for (java.lang.reflect.Method m : customGoalProcess.getClass().getMethods()) {
                if (!m.getName().equals("setGoalAndPath")) continue;
                if (m.getParameterCount() != 1) continue;

                try {
                    m.invoke(customGoalProcess, goal);
                    return true;
                } catch (Throwable inner) {
                }
            }

            return false;
        } catch (Throwable t) {
            AddonTemplate.LOG.warn("SquareSpiral: loose goal submission failed for {}: {}", target, t.toString());
            return false;
        }
    }

    /**
     * Execute Baritone's internal command manager with a command (reflective).
     */
    private boolean executeBaritoneCommand(Object primaryBaritone, String cmd) {
        try {
            // Try primaryBaritone.getCommandManager().execute(cmd)
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

            // Try provider.getCommandManager().execute(cmd)
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

            // Try any method on primaryBaritone that accepts a single String and looks like a command executor
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
            AddonTemplate.LOG.warn("SquareSpiral: executeBaritoneCommand failed for cmd='{}': {}", cmd, t.toString());
        }

        return false;
    }

    /**
     * Try to run Baritone's equivalent of '#goto x y z' via its command manager.
     */
    private boolean executeBaritoneGoto(Object primaryBaritone, BlockPos target) {
        String cmd = "goto " + target.getX() + " " + target.getY() + " " + target.getZ();
        return executeBaritoneCommand(primaryBaritone, cmd);
    }


    public SquareSpiral() {
        super(AddonTemplate.CATEGORY, "square-spiral", "Automatically walk a square spiral to cover every block inside the configured radius.");
    }

    @Override
    public void onActivate() {
        if (mc.player == null || mc.world == null) {
            info("Player or world not present — cannot start");
            toggle();
            return;
        }

        // Verify Baritone is available
        Object baritone = getPrimaryBaritone();
        if (baritone == null) {
            info("Baritone not installed — disabling Square Spiral");
            toggle();
            return;
        }

        // Cancel any existing Baritone pathing
        try {
            if (baritoneIsPathing(baritone)) baritoneCancel(baritone);
        } catch (Throwable ignored) {
        }

        // Capture start state
        startPos = mc.player.getBlockPos();
        startDim = mc.world.getRegistryKey();
        offsets.clear();
        offsets.addAll(generateSpiralOffsets(radius.get()));

        returningToStart = false;
        delayTicker = 0;
        goalsSubmitted = 0;

        info("Square Spiral started — radius=" + radius.get());

        // Submit first goal immediately (if any)
        submitNextGoalIfReady(baritone);
    }

    @Override
    public void onDeactivate() {
        // Cancel Baritone path if active
        try {
            Object baritone = getPrimaryBaritone();
            if (baritone != null && baritoneIsPathing(baritone)) baritoneCancel(baritone);
        } catch (Throwable ignored) {
        }

        offsets.clear();
        returningToStart = false;
        delayTicker = 0;
        goalsSubmitted = 0;
        currentTarget = null;
        currentTargetAgeTicks = 0;
        lastSubmissionAccepted = false;
        retryCountForCurrentTarget = 0;
        lastSubmittedGoalClassName = null;
        skipStreak = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || mc.player == null || mc.world == null) return;

        // Auto-disable on dimension change
        if (startDim != null && !mc.world.getRegistryKey().equals(startDim)) {
            info("Dimension changed — disabling Square Spiral");
            toggle();
            return;
        }

        Object baritone = getPrimaryBaritone();
        if (baritone == null) {
            info("Baritone not installed — disabling Square Spiral");
            toggle();
            return;
        }

        // Detect player death and treat according to 'useRelativeGoals'
        if (mc.player.isDead()) {
            // If using relative goals we can simply continue from the player's new position; otherwise inform the user and disable
            if (useRelativeGoals.get()) {
                info("Player died — resuming spiral relative to respawn position");
                // startPos remains the original anchor; when using relative goals we compute against current player pos when submitting
            } else {
                info("Player died — disabling Square Spiral (use-relative-goals=true to resume at death position)");
                toggle();
                return;
            }
        }

        // Completion / progress handling
        boolean isPathing = false;
        try {
            isPathing = baritoneIsPathing(baritone);
        } catch (Throwable ignored) {
        }

        // If we're returning to start, wait for Baritone to finish the return
        if (returningToStart) {
            if (!isPathing) {
                // returned to start
                info("Returned to start");
                returningToStart = false;
                if (autoDisable.get()) toggle();
            }
            return;
        }

        // If there is a current active target, wait until the player actually reaches it
        if (currentTarget != null) {
            // Age the current target so we don't mark it failed immediately after submission.
            currentTargetAgeTicks++;

            BlockPos playerPos = mc.player.getBlockPos();

            boolean arrived = playerPos.equals(currentTarget)
                || (playerPos.getX() == currentTarget.getX() && playerPos.getZ() == currentTarget.getZ() && Math.abs(mc.player.getY() - currentTarget.getY()) <= 1);

            if (arrived) {
                // Player reached the goal — clear and allow submission of the next goal after delay
                currentTarget = null;
                currentTargetAgeTicks = 0;
                delayTicker = 0;
            } else if (!isPathing && currentTargetAgeTicks >= CURRENT_TARGET_GRACE_TICKS) {
                // Baritone stopped pathing but player didn't arrive — allow one retry before skipping
                if (lastSubmissionAccepted && retryCountForCurrentTarget < MAX_CURRENT_TARGET_RETRIES) {
                    AddonTemplate.LOG.info("SquareSpiral: Baritone not pathing to {} after {} ticks — attempting command-fallback then loose goal", currentTarget, currentTargetAgeTicks);
                    info("Retrying target (command fallback): " + currentTarget.getX() + ", " + currentTarget.getZ());

                    // First try executing Baritone's internal goto command (works like #goto)
                    try {
                        if (executeBaritoneGoto(baritone, currentTarget)) {
                            retryCountForCurrentTarget++;
                            lastSubmissionAccepted = true;
                            lastSubmittedGoalClassName = "Command:goto";
                            currentTargetAgeTicks = 0;
                            return;
                        }
                    } catch (Throwable ignored) {
                    }

                    // Fallback to a looser API-level goal
                    if (baritoneSetLooseGoal(baritone, currentTarget)) {
                        retryCountForCurrentTarget++;
                        currentTargetAgeTicks = 0;
                        return;
                    } else {
                        AddonTemplate.LOG.warn("SquareSpiral: loose retry failed for {}", currentTarget);
                    }
                }

                AddonTemplate.LOG.warn("SquareSpiral: Baritone stopped before reaching {} — skipping target (age={} ticks)", currentTarget, currentTargetAgeTicks);
                info("Skipping unreachable target: " + currentTarget.getX() + ", " + currentTarget.getZ());
                currentTarget = null;
                currentTargetAgeTicks = 0;
                lastSubmissionAccepted = false;
                retryCountForCurrentTarget = 0;
                delayTicker = 0;
            } else {
                // Still pathing toward current target (or within grace period) — wait
                return;
            }
        }

        // If all spiral goals have been submitted and no active target remains — spiral complete
        if (offsets.isEmpty()) {
            if (!isPathing && currentTarget == null) {
                // Spiral finished
                info("Spiral complete");

                if (returnToStart.get()) {
                    returningToStart = true;
                    // Submit goal: startPos (absolute)
                    try {
                                if (!executeBaritoneGoto(baritone, startPos)) {
                            info("Failed to submit return-goal to Baritone (command fallback)");
                            if (autoDisable.get()) toggle();
                        } else {
                            // set the active target for the return trip so we wait until arrival
                            currentTarget = startPos;
                        }
                    } catch (Throwable t) {
                        // fallback: disable
                        info("Failed to submit return-goal to Baritone");
                        if (autoDisable.get()) toggle();
                    }
                } else if (autoDisable.get()) {
                    toggle();
                }
            }

            return;
        }

        // Submit next goal when Baritone is not currently pathing and delay has passed
        if (!isPathing) {
            if (delayTicker >= delayBetweenGoals.get()) {
                submitNextGoalIfReady(baritone);
                delayTicker = 0;
            } else {
                delayTicker++;
            }
        }
    }

    /**
     * Pull next offset(s) and submit the next pathable GoalBlock to Baritone.
     * If a target cannot be path-found, the module will skip it and continue with the next offset
     * instead of disabling the module.
     */
    private void submitNextGoalIfReady(Object baritone) {
        // Do not submit a new goal while there's an active target waiting for arrival
        if (currentTarget != null) return;

        while (!offsets.isEmpty()) {
            BlockPos offset = offsets.pollFirst();
            if (offset == null) return;

            // Compute a sensible Y at the target X/Z (avoid using start Y which may be invalid at other X/Z)
            BlockPos base = useRelativeGoals.get() ? mc.player.getBlockPos().add(offset) : startPos.add(offset);
            BlockPos target;
            try {
                BlockPos top = mc.world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, base);
                target = new BlockPos(base.getX(), top.getY(), base.getZ());
            } catch (Throwable t) {
                // Fallback to base if heightmap isn't available for some reason
                target = base;
            }

            try {
                // Submit using Baritone command fallback only (matches working '#goto').
                if (!executeBaritoneGoto(baritone, target)) {
                    // Skip this target and continue — do not disable the module
                    AddonTemplate.LOG.warn("SquareSpiral: command-fallback failed for target {} (base={})", target, base);

                    // Rate-limit info messages for repeated skips
                    skipStreak++;
                    if (skipStreak <= 3) {
                        info("Skipping un-pathable target: " + target.getX() + ", " + target.getZ());
                    } else if (skipStreak == 4) {
                        info("Skipping many targets — Baritone/API may be unavailable or targets unreachable");
                    }

                    continue;
                }

                // Successfully submitted a goal via command fallback — reset skip streak and record state
                skipStreak = 0;
                currentTarget = target;
                currentTargetAgeTicks = 0;
                lastSubmissionAccepted = true;
                retryCountForCurrentTarget = 0;
                goalsSubmitted++;

                AddonTemplate.LOG.info("SquareSpiral: submitted goal {} via command-fallback", target);

                if (placeTorchEveryN.get() > 0 && goalsSubmitted % placeTorchEveryN.get() == 0) {
                    // TODO: implement safe torch placement using Meteor/Baritone placement APIs (inventory checks, raycast, etc.)
                    info("(Torch placement requested but not implemented in this build)");
                }

                return; // submitted one goal — exit
            } catch (Throwable t) {
                // Log and skip this target rather than disabling
                AddonTemplate.LOG.warn("SquareSpiral: exception while submitting goal {} — skipping: {}", target, t.toString());
                info("Error submitting goal, skipping target: " + target.getX() + ", " + target.getZ());
                continue;
            }
        }

        // If we reach here, no offsets remain (either consumed or all skipped)
        AddonTemplate.LOG.info("SquareSpiral: no remaining pathable targets (offsets empty or all skipped)");
    }

    /**
     * Generate spiral offsets that cover every block with max(|x|,|z|) <= radius.
     * Offsets are produced in spiral order starting from (0,0).
     */
    private List<BlockPos> generateSpiralOffsets(int radius) {
        List<BlockPos> out = new ArrayList<>();
        final int total = (2 * radius + 1) * (2 * radius + 1);

        int x = 0, z = 0;
        out.add(new BlockPos(x, 0, z)); // center

        int steps = 1;
        int dir = 0; // 0:+X, 1:+Z, 2:-X, 3:-Z
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

        while (out.size() < total) {
            for (int repeat = 0; repeat < 2; repeat++) {
                for (int i = 0; i < steps; i++) {
                    x += dirs[dir][0];
                    z += dirs[dir][1];

                    if (Math.max(Math.abs(x), Math.abs(z)) <= radius) {
                        out.add(new BlockPos(x, 0, z));
                        if (out.size() >= total) return out;
                    }
                }
                dir = (dir + 1) & 3;
            }
            steps++;
        }

        return out;
    }
}
