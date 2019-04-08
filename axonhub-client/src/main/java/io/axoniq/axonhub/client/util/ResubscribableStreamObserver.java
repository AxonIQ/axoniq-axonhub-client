package io.axoniq.axonhub.client.util;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Wrapper around {@link StreamObserver} that re-subscribes on error received (if other side is still available).
 *
 * @author Milan Savic
 * @since 1.1.4
 */
public class ResubscribableStreamObserver<V> implements StreamObserver<V> {

    private static final Logger logger = LoggerFactory.getLogger(ResubscribableStreamObserver.class);

    private final StreamObserver<V> delegate;
    private final Consumer<Throwable> resubscribe;

    /**
     * Creates the Re-subscribable Stream Observer.
     *
     * @param delegate    the StreamObserver to delegate calls
     * @param resubscribe the re-subscription consumer - should implement the actual re-subscription
     */
    public ResubscribableStreamObserver(StreamObserver<V> delegate,
                                        Consumer<Throwable> resubscribe) {
        this.delegate = delegate;
        this.resubscribe = resubscribe;
    }

    @Override
    public void onNext(V value) {
        try {
            delegate.onNext(value);
        } catch (Exception | OutOfDirectMemoryError e) {
            onError(e);
        }
    }

    @Override
    public void onError(Throwable t) {
        logger.warn("A problem occurred in the stream.", t);
        delegate.onError(t);
        if (t instanceof StatusRuntimeException
                && ((StatusRuntimeException) t).getStatus().getCode()
                                               .equals(Status.UNAVAILABLE.getCode())) {
            return;
        }
        logger.info("Resubscribing.");
        resubscribe.accept(t);
    }

    @Override
    public void onCompleted() {
        delegate.onCompleted();
    }
}
