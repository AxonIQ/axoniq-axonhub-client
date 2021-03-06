/*
 * Copyright (c) 2018. AxonIQ
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.axoniq.axonhub.client.command;

import org.axonframework.commandhandling.CommandMessage;
/**
 * Calculate priority of message based on it content. Higher value means higher priority.
 * @author Marc Gathier
 */
public interface CommandPriorityCalculator {
    /**
     * default implementation returns 0 for all messages
     * @param command command to prioritize
     * @return priority
     */
    default int determinePriority(CommandMessage<?> command) {
        return 0;
    }
}
