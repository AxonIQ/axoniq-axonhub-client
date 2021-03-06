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
import io.axoniq.axonhub.client.DispatchInterceptors;
import io.axoniq.axonhub.client.ErrorCode;
import io.axoniq.axonhub.client.PlatformConnectionManager;
import io.axoniq.axonhub.client.util.ContextAddingInterceptor;
import io.axoniq.axonhub.client.util.ExceptionSerializer;
import io.axoniq.axonhub.client.util.FlowControllingStreamObserver;
import io.axoniq.axonhub.client.util.ResubscribableStreamObserver;
import io.axoniq.axonhub.client.util.TokenAddingInterceptor;
import io.axoniq.axonhub.grpc.CommandProviderInbound;
import io.axoniq.axonhub.grpc.CommandProviderOutbound;
import io.axoniq.axonhub.grpc.CommandServiceGrpc;
import io.grpc.ClientInterceptor;
import io.grpc.stub.StreamObserver;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.axonframework.commandhandling.CommandBus;
import org.axonframework.commandhandling.CommandCallback;
import org.axonframework.commandhandling.CommandExecutionException;
import org.axonframework.commandhandling.CommandMessage;
import org.axonframework.commandhandling.distributed.RoutingStrategy;
import org.axonframework.common.Registration;
import org.axonframework.messaging.MessageDispatchInterceptor;
import org.axonframework.messaging.MessageHandler;
import org.axonframework.messaging.MessageHandlerInterceptor;
import org.axonframework.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static io.axoniq.axonhub.client.util.ProcessingInstructionHelper.priority;


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
    private final DispatchInterceptors<CommandMessage<?>> dispatchInterceptors = new DispatchInterceptors<>();
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
        this.serializer = new CommandSerializer(serializer, configuration);
        this.platformConnectionManager = platformConnectionManager;
        this.routingStrategy = routingStrategy;
        this.priorityCalculator = priorityCalculator;
        this.configuration = configuration;
        this.commandRouterSubscriber = new CommandRouterSubscriber();
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
    public <C, R> void dispatch(CommandMessage<C> commandMessage, CommandCallback<? super C, R> commandCallback) {
        logger.debug("Dispatch with callback: {}", commandMessage.getCommandName());
        CommandMessage<C> command = dispatchInterceptors.intercept(commandMessage);
            CommandServiceGrpc.newStub(platformConnectionManager.getChannel())
                              .withInterceptors(interceptors)
                              .dispatch(serializer.serialize(command,
                                                             routingStrategy.getRoutingKey(command),
                                                             priorityCalculator.determinePriority(command)),
                                        new StreamObserver<CommandResponse>() {
                                            @Override
                                            public void onNext(CommandResponse commandResponse) {
                                                if (!commandResponse.hasMessage()) {
                                                    logger.debug("response received - {}", commandResponse);
                                                    R payload = null;
                                                    if (commandResponse.hasPayload()) {
                                                        try {
                                                            //noinspection unchecked
                                                            payload = (R) serializer.deserialize(commandResponse);
                                                        } catch (Exception ex) {
                                                            logger.info("Failed to deserialize payload - {} - {}",
                                                                        commandResponse.getPayload().getData(),
                                                                        ex.getCause().getMessage());
                                                        }
                                                    }

                                                    commandCallback.onSuccess(command, payload);
                                                } else {
                                                    commandCallback.onFailure(command,
                                                                              new CommandExecutionException(
                                                                                      commandResponse.getMessage()
                                                                                                     .getMessage(),
                                                                                      new RemoteCommandException(
                                                                                              commandResponse
                                                                                                      .getErrorCode(),
                                                                                              commandResponse
                                                                                                      .getMessage())));
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

    @Override
    public Registration registerHandlerInterceptor(
            MessageHandlerInterceptor<? super CommandMessage<?>> handlerInterceptor) {
        return localSegment.registerHandlerInterceptor(handlerInterceptor);
    }

    public void disconnect() {
        commandRouterSubscriber.disconnect();
    }

    protected class CommandRouterSubscriber {
        private final CopyOnWriteArraySet<String> subscribedCommands = new CopyOnWriteArraySet<>();
        private final PriorityBlockingQueue<Command> commandQueue;
        private final ExecutorService executor = Executors.newFixedThreadPool(configuration.getCommandThreads());
        private volatile boolean subscribing;


        private volatile StreamObserver<CommandProviderOutbound> subscriberStreamObserver;

        CommandRouterSubscriber() {
            platformConnectionManager.addReconnectListener(this::resubscribe);
            platformConnectionManager.addDisconnectListener(this::unsubscribeAll);
            commandQueue = new PriorityBlockingQueue<>(1000, Comparator.comparingLong(c -> -priority(c.getProcessingInstructionsList())));
            IntStream.range(0, configuration.getCommandThreads()).forEach( i -> executor.submit(this::commandExecutor));
        }

        private void commandExecutor() {
            logger.debug("Starting command Executor");
            Command command = null;
            boolean running = true;
            while(running) {
                try {
                    command = commandQueue.poll(10, TimeUnit.SECONDS);
                    if( command != null) {
                        try {
                            logger.debug("Received command: {}", command);
                            processCommand(command);
                        } catch (RuntimeException | OutOfDirectMemoryError e) {
                            logger.warn("commandExecutor exception on: ", command, e);
                        }
                    }
                } catch (InterruptedException e) {
                    logger.warn("Interrupted commandExecutor", e);
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }

        private void resubscribe() {
            if( subscribedCommands.isEmpty() || subscribing) return;

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
                logger.warn("Error while resubscribing - {}", ex.getMessage());
            }
        }

        public void subscribe(String command) {
            subscribing = true;
            subscribedCommands.add(command);
            try {
                StreamObserver<CommandProviderOutbound> subscriberStreamObserver = getSubscriberObserver();
                subscriberStreamObserver.onNext(CommandProviderOutbound.newBuilder().setSubscribe(
                        CommandSubscription.newBuilder()
                                .setCommand(command)
                                .setClientName(configuration.getClientName())
                                .setComponentName(configuration.getComponentName())
                                .setMessageId(UUID.randomUUID().toString())
                                .build()
                ).build());
            } catch (Exception sre) {
                logger.warn("Subscribe at axonhub platform failed - {}, trying again at later moment", sre.getMessage());
            } finally {
                subscribing = false;
            }
        }



        private void processCommand(Command command) {
            StreamObserver<CommandProviderOutbound> subscriberStreamObserver = getSubscriberObserver();
            try {
                dispatchLocal(serializer.deserialize(command), subscriberStreamObserver);
            } catch (Throwable throwable) {
                if( subscriberStreamObserver != null) {
                    logger.error("Error while dispatching command {} - {}", command.getName(), throwable.getMessage(), throwable);
                    CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                            CommandResponse.newBuilder()
                                           .setMessageIdentifier(UUID.randomUUID().toString())
                                           .setRequestIdentifier(command.getMessageIdentifier())
                                           .setErrorCode(ErrorCode.resolve(throwable).errorCode())
                                           .setMessage(ExceptionSerializer
                                                               .serialize(configuration.getClientName(), throwable))
                    ).build();

                    subscriberStreamObserver.onNext(response);
                }
            }
        }

        private synchronized StreamObserver<CommandProviderOutbound> getSubscriberObserver() {
            if (subscriberStreamObserver == null) {
                logger.info("Create new subscriber");
                StreamObserver<CommandProviderInbound> commandsFromRoutingServer = new StreamObserver<CommandProviderInbound>() {
                    @Override
                    public void onNext(CommandProviderInbound commandToSubscriber) {
                        logger.debug("Received from server: {}", commandToSubscriber);
                        switch (commandToSubscriber.getRequestCase()) {
                            case COMMAND:
                                commandQueue.add(commandToSubscriber.getCommand());
                                break;
                        }
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        logger.warn("Command Inbound Stream produced an error.", throwable);
                        subscriberStreamObserver = null;
                    }

                    @Override
                    public void onCompleted() {
                        logger.info("Command Inbound Stream completed.");
                        subscriberStreamObserver = null;
                    }
                };

                ResubscribableStreamObserver<CommandProviderInbound> resubscribableCommandFromRoutingServerStreamObserver = new ResubscribableStreamObserver<>(
                        commandsFromRoutingServer,
                        t -> resubscribe());

                StreamObserver<CommandProviderOutbound> stream = platformConnectionManager.getCommandStream(
                        resubscribableCommandFromRoutingServerStreamObserver,
                        interceptors);
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

        void unsubscribeAll() {
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
                    responseObserver.onNext(serializer.serialize(o, command.getIdentifier()));

                }

                @Override
                public void onFailure(CommandMessage<? extends C> commandMessage, Throwable throwable) {
                    CommandProviderOutbound response = CommandProviderOutbound.newBuilder().setCommandResponse(
                            CommandResponse.newBuilder()
                                           .setMessageIdentifier(UUID.randomUUID().toString())
                                           .setRequestIdentifier(command.getIdentifier())
                                           .setErrorCode(ErrorCode.resolve(throwable).errorCode())
                                           .setMessage(ExceptionSerializer.serialize(configuration.getClientName(), throwable))
                    ).build();

                    responseObserver.onNext(response);
                    logger.info("DispatchLocal: failure {} - {}", command.getCommandName(), throwable.getMessage(), throwable);
                }
            });
        }

        public void disconnect() {
            if( subscriberStreamObserver != null)
                subscriberStreamObserver.onCompleted(); //onError(new AxonHubException("AXONIQ-0001", "Cancelled by client"));
        }
    }

    public Registration registerDispatchInterceptor(MessageDispatchInterceptor<? super CommandMessage<?>> dispatchInterceptor) {
        return dispatchInterceptors.registerDispatchInterceptor(dispatchInterceptor);
    }
}
