package com.example.addon.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.example.addon.modules.SquareSpiral;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.command.CommandSource;

/**
 * .spiral [radius] [returnToStart] [autoDisable] [useRelativeGoals] [delay]
 *
 * All arguments are optional and positional. Booleans are parsed with
 * Boolean.parseBoolean (true/false).
 */
public class CommandSpiral extends Command {
    public CommandSpiral() {
        super("spiral", "Start a square spiral (optional args: radius returnToStart autoDisable useRelativeGoals delay)");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        // No-arg: use module defaults
        builder.executes(context -> {
            applyAndStart(null, null, null, null, null);
            return SINGLE_SUCCESS;
        });

        // radius + optional positional args (returnToStart, autoDisable, useRelativeGoals, delay)
        builder.then(
            argument("radius", IntegerArgumentType.integer(1, 512)).executes(context -> {
                int r = IntegerArgumentType.getInteger(context, "radius");
                applyAndStart(r, null, null, null, null);
                return SINGLE_SUCCESS;
            })
            .then(
                argument("returnToStart", StringArgumentType.word()).executes(context -> {
                    int r = IntegerArgumentType.getInteger(context, "radius");
                    boolean ret = Boolean.parseBoolean(StringArgumentType.getString(context, "returnToStart"));
                    applyAndStart(r, ret, null, null, null);
                    return SINGLE_SUCCESS;
                })
                .then(
                    argument("autoDisable", StringArgumentType.word()).executes(context -> {
                        int r = IntegerArgumentType.getInteger(context, "radius");
                        boolean ret = Boolean.parseBoolean(StringArgumentType.getString(context, "returnToStart"));
                        boolean ad = Boolean.parseBoolean(StringArgumentType.getString(context, "autoDisable"));
                        applyAndStart(r, ret, ad, null, null);
                        return SINGLE_SUCCESS;
                    })
                    .then(
                        argument("useRelativeGoals", StringArgumentType.word()).executes(context -> {
                            int r = IntegerArgumentType.getInteger(context, "radius");
                            boolean ret = Boolean.parseBoolean(StringArgumentType.getString(context, "returnToStart"));
                            boolean ad = Boolean.parseBoolean(StringArgumentType.getString(context, "autoDisable"));
                            boolean rel = Boolean.parseBoolean(StringArgumentType.getString(context, "useRelativeGoals"));
                            applyAndStart(r, ret, ad, rel, null);
                            return SINGLE_SUCCESS;
                        })
                        .then(
                            argument("delay", IntegerArgumentType.integer(0, 200)).executes(context -> {
                                int r = IntegerArgumentType.getInteger(context, "radius");
                                boolean ret = Boolean.parseBoolean(StringArgumentType.getString(context, "returnToStart"));
                                boolean ad = Boolean.parseBoolean(StringArgumentType.getString(context, "autoDisable"));
                                boolean rel = Boolean.parseBoolean(StringArgumentType.getString(context, "useRelativeGoals"));
                                int d = IntegerArgumentType.getInteger(context, "delay");
                                applyAndStart(r, ret, ad, rel, d);
                                return SINGLE_SUCCESS;
                            })
                        )
                    )
                )
            )
        );
    }

    private void applyAndStart(Integer radius, Boolean returnToStart, Boolean autoDisable, Boolean useRelativeGoals, Integer delay) {
        SquareSpiral module = Modules.get().get(SquareSpiral.class);
        if (module == null) {
            info("SquareSpiral module not found");
            return;
        }

        if (radius != null) module.radius.set(radius);
        if (returnToStart != null) module.returnToStart.set(returnToStart);
        if (autoDisable != null) module.autoDisable.set(autoDisable);
        if (useRelativeGoals != null) module.useRelativeGoals.set(useRelativeGoals);
        if (delay != null) module.delayBetweenGoals.set(delay);

        // Feedback
        info(String.format("Spiral config: radius=%d returnToStart=%s autoDisable=%s useRelativeGoals=%s delay=%d",
            module.radius.get(), module.returnToStart.get(), module.autoDisable.get(), module.useRelativeGoals.get(), module.delayBetweenGoals.get()));

        // Start module if not already active
        if (!module.isActive()) module.toggle();
    }
}
