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

package io.axoniq.axonhub.client.processor.grpc;

import io.axoniq.platform.grpc.EventProcessorInfo;
import io.axoniq.platform.grpc.EventProcessorInfo.EventTrackerInfo;
import io.axoniq.platform.grpc.PlatformInboundInstruction;
import org.axonframework.eventhandling.EventTrackerStatus;
import org.axonframework.eventhandling.TrackingEventProcessor;

import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toList;

/**
 * Created by Sara Pellegrini on 15/03/2018.
 * sara.pellegrini@gmail.com
 */
public class TrackingEventProcessorInfoMessage implements PlatformInboundMessage {

    private final TrackingEventProcessor processor;

    public TrackingEventProcessorInfoMessage(TrackingEventProcessor processor) {
        this.processor = processor;
    }

    @Override
    public PlatformInboundInstruction instruction() {
        Map<Integer, EventTrackerStatus> statusMap = processor.processingStatus();


        List<EventTrackerInfo> trackers = statusMap
                .entrySet()
                .stream()
                .map(e -> EventTrackerInfo.newBuilder()
                                          .setSegmentId(e.getKey())
                                          .setCaughtUp(e.getValue().isCaughtUp())
                                          .setReplaying(e.getValue().isReplaying())
                                          .setOnePartOf(e.getValue().getSegment().getMask()+1)

                     .build())
                .collect(toList());

        EventProcessorInfo msg = EventProcessorInfo.newBuilder()
                                                   .setProcessorName(processor.getName())
                                                   .setMode("Tracking")
                                                   .setActiveThreads(processor.activeProcessorThreads())
                                                   .setRunning(processor.isRunning())
                                                   .setError(processor.isError())
                                                   .addAllEventTrackersInfo(trackers)
                                                   .build();
        return PlatformInboundInstruction
                .newBuilder()
                .setEventProcessorInfo(msg)
                .build();
    }
}
