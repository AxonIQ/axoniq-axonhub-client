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
package io.axoniq.axonhub.example;

import io.axoniq.axonhub.client.AxonHubConfiguration;
import io.axoniq.axonhub.client.PlatformConnectionManager;
import io.axoniq.axonhub.client.command.AxonHubCommandBus;
import io.axoniq.axonhub.client.event.axon.AxonHubEvenProcessorInfoConfiguration;
import io.axoniq.axonhub.client.event.axon.AxonHubEventStore;
import io.axoniq.axonhub.client.query.AxonHubQueryBus;
import io.axoniq.axonhub.client.query.QueryPriorityCalculator;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.SimpleCommandBus;
import org.axonframework.commandhandling.distributed.AnnotationRoutingStrategy;
import org.axonframework.config.Configuration;
import org.axonframework.config.DefaultConfigurer;
import org.axonframework.config.EventHandlingConfiguration;
import org.axonframework.config.ModuleConfiguration;
import org.axonframework.eventhandling.EventBus;
import org.axonframework.queryhandling.QueryBus;
import org.axonframework.queryhandling.SimpleQueryBus;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.json.JacksonSerializer;

/**
 * @author Marc Gathier
 */
public class AxonStarter {

    public static void main(String[] args) {
        AxonHubConfiguration axonhubConfiguration = AxonHubConfiguration.newBuilder("localhost:8024", "AxonStarter").build();
        PlatformConnectionManager platformConnectionManager = new PlatformConnectionManager(axonhubConfiguration);

        ModuleConfiguration moduleConfiguration =
                new AxonHubEvenProcessorInfoConfiguration(new EventHandlingConfiguration(),
                                                          platformConnectionManager,
                                                          axonhubConfiguration);
        Serializer serializer = new JacksonSerializer();
        EventBus axonHubEventStore = new AxonHubEventStore(axonhubConfiguration, platformConnectionManager, serializer);
        CommandBus localSegment = new SimpleCommandBus();
        CommandBus axonHubCommandBus = new AxonHubCommandBus(platformConnectionManager, axonhubConfiguration, localSegment, serializer,
                new AnnotationRoutingStrategy());

        QueryBus localQueryBus = new SimpleQueryBus();
        QueryBus axonHubQueryBus = new AxonHubQueryBus(platformConnectionManager, axonhubConfiguration, localQueryBus, serializer,
                new QueryPriorityCalculator() {});

        Configuration config = DefaultConfigurer.defaultConfiguration()
                                                .configureEventBus(c -> axonHubEventStore)
                                                .configureCommandBus(c -> axonHubCommandBus)
                                                .configureQueryBus(c -> axonHubQueryBus)
                                                .registerModule(moduleConfiguration)
                                                // more configuration for Axon
                                                .configureSerializer(c -> serializer)
                                                .buildConfiguration();

        config.start();
    }
}
