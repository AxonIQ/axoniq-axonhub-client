package io.axoniq.axonhub.example;

import org.axonframework.queryhandling.QueryBus;
import org.axonframework.queryhandling.SimpleQueryBus;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;

@SpringBootTest
@RunWith(SpringRunner.class)
public class ExampleApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    public void testApplicationStartsWithAxon31() {
        assertEquals(SimpleQueryBus.class, applicationContext.getBean(QueryBus.class).getClass());
    }
}
