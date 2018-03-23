package io.axoniq.axonhub.client.processor;

/**
 * Created by Sara Pellegrini on 09/03/2018.
 * sara.pellegrini@gmail.com
 */
public interface AxonHubEventProcessorInfoSource {

    void notifyInformation();

    class Fake implements AxonHubEventProcessorInfoSource {

        private int notifyCalls;

        @Override
        public void notifyInformation() {
            notifyCalls++;
        }

        public int notifyCalls() {
            return notifyCalls;
        }
    }

}