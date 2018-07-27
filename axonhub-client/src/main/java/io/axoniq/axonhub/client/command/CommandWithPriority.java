package io.axoniq.axonhub.client.command;

import io.axoniq.axonhub.Command;

import java.util.Comparator;

import static io.axoniq.axonhub.client.util.ProcessingInstructionHelper.priority;

/**
 * Author: marc
 */
class CommandWithPriority implements Comparable<CommandWithPriority> {

    private final Command command;
    private final long priority;
    private final long timestamp;

    CommandWithPriority(Command command) {
        this.command = command;
        this.priority = priority(command.getProcessingInstructionsList());
        this.timestamp = System.currentTimeMillis();
    }

    public Command getCommand() {
        return command;
    }

    public long getPriority() {
        return priority;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public int compareTo(CommandWithPriority o) {
        return Comparator.comparing(CommandWithPriority::getPriority)
                         .thenComparing(CommandWithPriority::getTimestamp)
                         .compare(this, o);
    }
}
