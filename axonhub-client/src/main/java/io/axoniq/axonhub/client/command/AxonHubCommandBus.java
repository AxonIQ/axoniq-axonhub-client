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

import io.axoniq.axonhub.Command;
import io.axoniq.axonhub.CommandResponse;
import io.axoniq.axonhub.CommandSubscription;
import io.axoniq.axonhub.client.AxonHubConfiguration;
import io.axoniq.axonhub.client.PlatformConnectionManager;
import io.axoniq.axonhub.client.util.ContextAddingInterceptor;
import io.axoniq.axonhub.client.util.FlowControllingStreamObserver;
import io.axoniq.axonhub.client.util.TokenAddingInterceptor;
import io.axoniq.axonhub.grpc.CommandProviderInbound;
import io.axoniq.axonhub.grpc.CommandProviderOutbound;
import io.axoniq.axonhub.grpc.CommandServiceGrpc;
import io.grpc.ClientInterceptor;
import io.grpc.stub.StreamObserver;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandExecutionException;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.distributed.RoutingStrategy;
import org.axonframework.common.Registration;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;


/**
 * Axon CommandBus implementation that connects to AxonHub to submit and receive commands.
 * @author Marc Gathier
 */
public class AxonHubCommandBus implements CommandBus {
    private final CommandBus localSegment;
    private final CommandRouterSubscriber commandRouterSubscriber;
    private final PlatformConnectionManager platformConnectionManager;
    private final RoutingStrategy routingStrategy;
    private final CommandPriorityCalculator priorityCalculator;
    private final CommandSerializer serializer;
    private final AxonHubConfiguration configuration;
    private final ClientInterceptor[] interceptors;
    private Logger logger = LoggerFactory.getLogger(AxonHubCommandBus.class);

    public AxonHubCommandBus(PlatformConnectionManager platformConnectionManager, AxonHubConfiguration configuration,
                             CommandBus localSegment, Serializer serializer, RoutingStrategy routingStrategy) {
        this( platformConnectionManager, configuration, localSegment, serializer, routingStrategy, new CommandPriorityCalculator(){});
    }
    /**
     * @param platformConnectionManager creates connection to AxonHub platform
     * @param configuration contains client and component names used to identify the application in AxonHub
     * @param localSegment handles incoming commands
     * @param serializer serializer/deserializer for command requests and responses
     * @param routingStrategy determines routing key based on command message
     * @param priorityCalculator calculates the request priority based on the content and adds it to the request
     */
    public AxonHubCommandBus(PlatformConnectionManager platformConnectionManager, AxonHubConfiguration configuration,
                             CommandBus localSegment, Serializer serializer, RoutingStrategy routingStrategy, CommandPriorityCalculator priorityCalculator) {
        this.localSegment = localSegment;
        this.serializer = new CommandSerializer(serializer);
        this.platformConnectionManager = platformConnectionManager;
        this.routingStrategy = routingStrategy;
        this.priorityCalculator = priorityCalculator;
        this.commandRouterSubscriber = new CommandRouterSubscriber();
        this.configuration = configuration;
        interceptors = new ClientInterceptor[]{ new TokenAddingInterceptor(configuration.getToken()),
                new ContextAddingInterceptor(configuration.getContext())};
    }

    @Override
    public <C> void dispatch(CommandMessage<C> command) {
        dispatch(command, new CommandCallback<C, Object>() {
            @Override
            public void onSuccess(CommandMessage<? extends C> commandMessage, Object o) {
            }

            @Override
            public void onFailure(CommandMessage<? extends C> commandMessage, Throwable throwable) {
            }
        });
    }


    @Override
    public <C, R> void dispatch(CommandMessage<C> command, CommandCallback<? super C, R> commandCallback) {
        logger.debug("Dispatch with callback: {}", command.getCommandName());
        CommandServiceGrpc.newStub(platformConnectionManager.getChannel())
                .withInterceptors(interceptors)
                .dispatch(serializer.serialize(command, routingStrategy.getRoutingKey(command), priorityCalculator.determinePriority(command)),
                        new StreamObserver<CommandResponse>() {
                            @Override
                            public void onNext(CommandResponse commandResponse) {
                                if (commandResponse.getSuccess()) {
                                    logger.debug("response received - {}", commandResponse);
                                    R payload = null;
                                    if (commandResponse.hasPayload()) {
                                        try {
                                            //noinspection unchecked
                                            payload = (R) serializer.deserializePayload(commandResponse.getPayload());
                                        } catch (Exception ex) {
                                            logger.info("Failed to deserialize payload - {} - {}", commandResponse.getPayload().getData(), ex.getCause().getMessage());
                                        }
                                    }

                                    commandCallback.onSuccess(command, payload);
                                } else {
                                    commandCallback.onFailure(command, new CommandExecutionException(commandResponse.getMessage(), null));
                                }
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                commandCallback.onFailure(command, throwable);
                            }

                            @Override
                            public void onCompleted() {
                            }
                        });
    }

    @Override
    public Registration subscribe(String s, MessageHandler<? super CommandMessage<?>> messageHandler) {
        logger.debug("Subscribe: {}", s);
        commandRouterSubscriber.subscribe(s);
        return new AxonHubRegistration(localSegment.subscribe(s, messageHandler), () -> commandRouterSubscriber.unsubscribe(s));
    }

    protected class CommandRouterSubscriber {
        private final CopyOnWriteArraySet<String> subscribedCommands = new CopyOnWriteArraySet<>();

        private volatile StreamObserver<CommandProviderOutbound> subscriberStreamObserver;

        public CommandRouterSubscriber() {
            platformConnectionManager.addReconnectListener(this::resubscribe);
            platformConnectionManager.addDisconnectListener(this::unsubscribeAll);
        }

        private void resubscribe() {
            try {
                StreamObserver<CommandProviderOutbound> subscriberStreamObserver = getSubscriberObserver();
                subscribedCommands.forEach(command -> subscriberStreamObserver.onNext(CommandProviderOutbound.newBuilder().setSubscribe(
                        CommandSubscription.newBuilder()
                                .setCommand(command)
                                .setComponentName(configuration.getComponentName())
                                .setClientName(configuration.getClientName())
                                .setMessageId(UUID.randomUUID().toString())
                                .build()
                ).build()));
            } catch (Exception ex) {
                logger.debug("Error while resubscribing - {}", ex.getMessage());
            }
        }

        public void subscribe(String command) {
            subscribedCommands.add(command);
            try {
                StreamObserver<CommandProviderOutbound> subscriberStreamObserver = getSubscriberObserver();
                subscriberStreamObserver.onNext(CommandProviderOutbound.newBuilder().setSubscribe(
                        CommandSubscription.newBuilder()
                                .setCommand(command)
                                .setClientName(configuration.getClientName())
                                .setMessageId(UUID.randomUUID().toString())
                                .build()
                ).build());
            } catch (Exception sre) {
                logger.warn("Subscribe at axonhub platform failed - {}, trying again at later moment", sre.getMessage());
            }
        }

        private void processCommand(Command command) {
            StreamObserver<CommandProviderOutbound> subscriberStreamObserver = getSubscriberObserver();
            try {
                dispatchLocal(serializer.deserialize(command), subscriberStreamObserver);
            } catch (Throwable throwable) {
                logger.error("Error while dispatching command {} - {}", command.getName(), throwable.getMessage());
                CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                        CommandResponse.newBuilder().setMessageIdentifier(command.getMessageIdentifier())
                                .setSuccess(false)
                                .setMessage(throwable.getMessage())
                                .build()
                ).build();

                subscriberStreamObserver.onNext(response);
            }
        }

        private synchronized StreamObserver<CommandProviderOutbound> getSubscriberObserver() {
            if (subscriberStreamObserver == null) {
                StreamObserver<CommandProviderInbound> commandsFromRoutingServer = new StreamObserver<CommandProviderInbound>() {
                    @Override
                    public void onNext(CommandProviderInbound commandToSubscriber) {
                        logger.debug("Received from server: {}", commandToSubscriber);
                        switch (commandToSubscriber.getRequestCase()) {
                            case COMMAND:
                                processCommand(commandToSubscriber.getCommand());
                                break;
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        logger.warn("Received error from server: {}", throwable.getMessage());
                        subscriberStreamObserver = null;
                        platformConnectionManager.scheduleReconnect();
                    }

                    @Override
                    public void onCompleted() {
                        logger.debug("Received completed from server");
                        subscriberStreamObserver = null;
                        platformConnectionManager.scheduleReconnect();
                    }
                };

                StreamObserver<CommandProviderOutbound> stream = platformConnectionManager.getCommandStream(commandsFromRoutingServer, interceptors);
                subscriberStreamObserver = new FlowControllingStreamObserver<>(stream,
                        configuration,
                        flowControl -> CommandProviderOutbound.newBuilder().setFlowControl(flowControl).build(),
                        t -> t.getRequestCase().equals(CommandProviderOutbound.RequestCase.COMMANDRESPONSE)).sendInitialPermits();

            }
            return subscriberStreamObserver;
        }

        public void unsubscribe(String command) {
            subscribedCommands.remove(command);
            try {
                getSubscriberObserver().onNext(CommandProviderOutbound.newBuilder().setUnsubscribe(
                        CommandSubscription.newBuilder()
                                .setCommand(command)
                                .setClientName(configuration.getClientName())
                                .setMessageId(UUID.randomUUID().toString())
                                .build()
                ).build());
            } catch (Exception ignored) {
            }
        }

        public void unsubscribeAll() {
            for (String command : subscribedCommands) {
                try {
                    getSubscriberObserver().onNext(CommandProviderOutbound.newBuilder().setUnsubscribe(
                            CommandSubscription.newBuilder()
                                    .setCommand(command)
                                    .setClientName(configuration.getClientName())
                                    .setMessageId(UUID.randomUUID().toString())
                                    .build()
                    ).build());
                } catch (Exception ignored) {
                }
            }
            subscriberStreamObserver = null;
        }

        private <C> void dispatchLocal(CommandMessage<C> command, StreamObserver<CommandProviderOutbound> responseObserver) {
            logger.debug("DispatchLocal: {}", command.getCommandName());
            localSegment.dispatch(command, new CommandCallback<C, Object>() {
                @Override
                public void onSuccess(CommandMessage<? extends C> commandMessage, Object o) {
                    logger.debug("DispatchLocal: done {}", command.getCommandName());
                    CommandResponse.Builder responseBuilder = CommandResponse.newBuilder().setMessageIdentifier(command.getIdentifier())
                            .setSuccess(true);
                    if (o != null) {
                        responseBuilder.setPayload(serializer.serializePayload(o));
                    }
                    CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                            responseBuilder.build()
                    ).build();
                    responseObserver.onNext(response);

                }

                @Override
                public void onFailure(CommandMessage<? extends C> commandMessage, Throwable throwable) {

                    CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                            CommandResponse.newBuilder().setMessageIdentifier(command.getIdentifier())
                                    .setSuccess(false)
                                    .setMessage(throwable.getMessage())
                                    .build()
                    ).build();

                    responseObserver.onNext(response);
                    logger.info("DispatchLocal: failure {} - {}", command.getCommandName(), throwable.getMessage());
                }
            });
        }
    }
}