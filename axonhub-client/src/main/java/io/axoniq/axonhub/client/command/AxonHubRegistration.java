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

import org.axonframework.common.Registration;

/**
 * @author Marc Gathier
 * Wrapper around standard Axon framework registration.
 * Notifies messaging server when registration is cancelled or closed, and delegates the close/cancel to the normal registration.
 */
public class AxonHubRegistration implements Registration {
    private final Registration wrappedRegistration;
    private final Runnable closeCallback;

    public AxonHubRegistration(Registration wrappedRegistration, Runnable closeCallback) {
        this.wrappedRegistration = wrappedRegistration;
        this.closeCallback = closeCallback;
    }

    @Override
    public void close() {
        wrappedRegistration.close();
        closeCallback.run();
    }

    @Override
    public boolean cancel() {
        boolean result = wrappedRegistration.cancel();
        if( result)
            closeCallback.run();
        return result;
    }
}
