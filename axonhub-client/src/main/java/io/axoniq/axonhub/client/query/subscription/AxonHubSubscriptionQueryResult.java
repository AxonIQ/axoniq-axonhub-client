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

package io.axoniq.axonhub.client.query.subscription;

import io.axoniq.axonhub.QueryResponse;
import io.axoniq.axonhub.QueryUpdate;
import io.axoniq.axonhub.QueryUpdateCompleteExceptionally;
import io.axoniq.axonhub.SubscriptionQuery;
import io.axoniq.axonhub.SubscriptionQueryRequest;
import io.axoniq.axonhub.SubscriptionQueryResponse;
import io.axoniq.axonhub.client.AxonHubConfiguration;
import io.axoniq.axonhub.client.Publisher;
import io.axoniq.axonhub.client.query.RemoteQueryException;
import io.axoniq.axonhub.client.util.FlowControllingStreamObserver;
import io.axoniq.axonhub.grpc.FlowControl;
import io.grpc.stub.StreamObserver;
import org.axonframework.common.Registration;
import org.axonframework.queryhandling.DefaultSubscriptionQueryResult;
import org.axonframework.queryhandling.QueryResponseMessage;
import org.axonframework.queryhandling.SubscriptionQueryBackpressure;
import org.axonframework.queryhandling.SubscriptionQueryMessage;
import org.axonframework.queryhandling.SubscriptionQueryResult;
import org.axonframework.queryhandling.SubscriptionQueryUpdateMessage;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.axoniq.axonhub.SubscriptionQueryRequest.newBuilder;
import static java.util.Optional.ofNullable;

/**
 *
 * SubscriptionQueryResult that emits initial response and update when subscription query response message is received.
 *
 * @author Sara Pellegrini
 */
class AxonHubSubscriptionQueryResult implements
        Supplier<SubscriptionQueryResult<QueryResponse, QueryUpdate>>,
        StreamObserver<SubscriptionQueryResponse> {

    private final FlowControllingStreamObserver<SubscriptionQueryRequest> requestObserver;

    private final SubscriptionQueryResult<QueryResponse, QueryUpdate> result;

    private final SubscriptionQuery subscriptionQuery;

    private final FluxSink<QueryUpdate> updateMessageFluxSink;

    private final Runnable onDispose;

    private MonoSink<QueryResponse> initialResultSink;

    AxonHubSubscriptionQueryResult(
            SubscriptionQuery query,
            Function<StreamObserver<SubscriptionQueryResponse>, StreamObserver<SubscriptionQueryRequest>> openStreamFn,
            AxonHubConfiguration configuration,
            SubscriptionQueryBackpressure backPressure,
            int bufferSize, Runnable onDispose) {
        this.onDispose = onDispose;

        EmitterProcessor<QueryUpdate> processor = EmitterProcessor.create(bufferSize);

        this.subscriptionQuery = query;
        this.updateMessageFluxSink = processor.sink(backPressure.getOverflowStrategy());

        StreamObserver<SubscriptionQueryRequest> subscription = openStreamFn.apply(this);
        Function<FlowControl, SubscriptionQueryRequest> requestMapping = flowControl ->
                newBuilder().setFlowControl(SubscriptionQuery.newBuilder(subscriptionQuery).setNumberOfPermits(flowControl.getPermits())).build();
        requestObserver = new FlowControllingStreamObserver<>(subscription, configuration, requestMapping, t -> false);
        requestObserver.sendInitialPermits();
        requestObserver.onNext(newBuilder().setSubscribe(subscriptionQuery).build());
        updateMessageFluxSink.onDispose(requestObserver::onCompleted);
        Registration registration = () -> {
            updateMessageFluxSink.complete();
            return true;
        };
        Mono<QueryResponse> mono = Mono.create(sink -> initialResult(sink, requestObserver::onNext));
        this.result = new DefaultSubscriptionQueryResult<>(mono,  processor.replay().autoConnect(), registration);
    }

    private void initialResult(MonoSink<QueryResponse> sink, Publisher<SubscriptionQueryRequest> publisher){
        initialResultSink = sink;
        publisher.publish(newBuilder().setGetInitialResult(subscriptionQuery).build());
    }


    @Override
    public void onNext(SubscriptionQueryResponse response) {
        requestObserver.markConsumed(1);
        switch (response.getResponseCase()) {
            case INITIAL_RESPONSE:
                ofNullable(initialResultSink).ifPresent(sink -> sink.success(response.getInitialResponse()));
                break;
            case UPDATE:
                updateMessageFluxSink.next(response.getUpdate());
                break;
            case COMPLETE:
                requestObserver.onCompleted();
                complete();
                break;
            case COMPLETE_EXCEPTIONALLY:
                requestObserver.onCompleted();
                QueryUpdateCompleteExceptionally exceptionally = response.getCompleteExceptionally();
                Throwable e = new RemoteQueryException(exceptionally.getErrorCode(), exceptionally.getMessage());
                completeExceptionally(e);
                break;
        }
    }

    @Override
    public void onError(Throwable t) {
        completeExceptionally(t);
    }

    @Override
    public void onCompleted() {
        complete();
    }

    @Override
    public SubscriptionQueryResult<QueryResponse, QueryUpdate> get() {
        return this.result;
    }


    private void complete(){
        ofNullable(initialResultSink).ifPresent(sink -> sink.error(new IllegalStateException("Subscription Completed")));
        updateMessageFluxSink.complete();
        onDispose.run();
    }
    private void completeExceptionally(Throwable t){
        ofNullable(initialResultSink).ifPresent(sink -> sink.error(t));
        updateMessageFluxSink.error(t);
        onDispose.run();
    }

}
