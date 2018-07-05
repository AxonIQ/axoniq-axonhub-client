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

package io.axoniq.axonhub.client.query;

import io.axoniq.axonhub.QueryRequest;
import io.axoniq.axonhub.QueryResponse;
import io.axoniq.axonhub.client.AxonHubConfiguration;
import org.axonframework.queryhandling.GenericQueryMessage;
import org.axonframework.queryhandling.GenericQueryResponseMessage;
import org.axonframework.queryhandling.QueryMessage;
import org.axonframework.queryhandling.QueryResponseMessage;
import org.axonframework.serialization.Serializer;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.junit.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.axonframework.queryhandling.responsetypes.ResponseTypes.instanceOf;
import static org.junit.Assert.*;

/**
 * Created by Sara Pellegrini on 28/06/2018.
 * sara.pellegrini@gmail.com
 */
public class QuerySerializerTest {

    private final Serializer xStreamSerializer = new XStreamSerializer();

    private final Serializer jacksonSerializer = new JacksonSerializer();

    private final AxonHubConfiguration configuration = new AxonHubConfiguration() {{
        this.setClientName("client");
        this.setComponentName("component");
    }};

    private final QuerySerializer testSubject = new QuerySerializer(jacksonSerializer, xStreamSerializer, configuration);

    @Test
    public void testSerializeRequest(){
        QueryMessage<String, Integer> message = new GenericQueryMessage<>("Test","MyQueryName",instanceOf(int.class));
        QueryRequest queryRequest = testSubject.serializeRequest(message, 5, 10, 1);
        QueryMessage<Object, Object> deserialized = testSubject.deserializeRequest(queryRequest);
        assertEquals(message.getIdentifier(), deserialized.getIdentifier());
        assertEquals(message.getQueryName(), deserialized.getQueryName());
        assertEquals(message.getMetaData(), deserialized.getMetaData());
        assertTrue(message.getResponseType().matches(deserialized.getResponseType().responseMessagePayloadType()));
        assertEquals(message.getPayload(), deserialized.getPayload());
        assertEquals(message.getPayloadType(), deserialized.getPayloadType());
    }

    @Test
    public void testSerializeResponse(){
        Map<String, ?> metadata = new HashMap<String, Object>() {{
            this.put("firstKey", "firstValue");
            this.put("secondKey", "secondValue");
        }};
        QueryResponseMessage message = new GenericQueryResponseMessage<>(BigDecimal.class, BigDecimal.ONE, metadata);
        QueryResponse grpcMessage = testSubject.serializeResponse(message, "requestMessageId");
        QueryResponseMessage<Object> deserialized = testSubject.deserializeResponse(grpcMessage);
        assertEquals(message.getIdentifier(), deserialized.getIdentifier());
        assertEquals(message.getMetaData(), deserialized.getMetaData());
        assertEquals(message.getPayloadType(), deserialized.getPayloadType());
        assertEquals(message.getPayload(),deserialized.getPayload());
    }

}