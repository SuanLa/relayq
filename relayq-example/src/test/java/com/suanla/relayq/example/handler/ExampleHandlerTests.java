package com.suanla.relayq.example.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.suanla.relayq.core.handler.ParamDeserializationException;
import com.suanla.relayq.core.handler.TaskContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExampleHandlerTests {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Test
    void echoHandlerAcceptsTypedPayload() {
        EchoHandler handler = new EchoHandler();

        assertDoesNotThrow(() -> handler.execute(context(
                "{\"message\":\"hello\"}",
                0)));
    }

    @Test
    void flakyHandlerUsesPersistedRetryCount() {
        FlakyHandler handler = new FlakyHandler();

        assertThrows(
                IllegalStateException.class,
                () -> handler.execute(context("{\"fail_times\":2}", 0)));
        assertDoesNotThrow(
                () -> handler.execute(context("{\"fail_times\":2}", 2)));
    }

    @Test
    void poisonHandlerRaisesNonRetryableDeserializationFailure() {
        PoisonHandler handler = new PoisonHandler();

        assertThrows(
                ParamDeserializationException.class,
                () -> handler.execute(context(
                        "{\"required_number\":\"not-a-number\"}",
                        0)));
    }

    @Test
    void slowHandlerValidatesItsPayloadAsDeserializationFailure() {
        SlowHandler handler = new SlowHandler();

        assertThrows(
                ParamDeserializationException.class,
                () -> handler.execute(context("{\"sleep_millis\":-1}", 0)));
    }

    private TaskContext context(String rawParams, int retryCount) {
        return new TaskContext(
                1L,
                "handler-test",
                "test-handler",
                rawParams,
                "handler-test-trace",
                retryCount + 1,
                retryCount,
                3,
                LocalDateTime.now(),
                objectMapper);
    }
}
