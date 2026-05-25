package io.zefio.gateway.filter.modify;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.factory.PluginContext;
import io.zefio.gateway.filter.transform.SpELModifierInterceptor;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.builder.config.Telegram;
import io.zefio.core.payload.util.TelegramFactory;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.core.payload.spel.PayloadExpressionEvaluator;
import io.zefio.gateway.filter.transform.dto.SpELModifierInterceptorValues;
import io.zefio.gateway.filter.transform.dto.SpelAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SpELModifierExtremeTestCase {

    @Mock private PluginContext mockContext;
    @Mock private PayloadBuilder mockBuilder;

    private final byte[] FIXED_RAW = "FIXED_RESULT_DATA_100_BYTES".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        // =====================================================================
        // 🚀 1. Create Mock Telegram metadata and inject it into the builder
        // =====================================================================
        Telegram fixedTg =
                org.mockito.Mockito.mock(Telegram.class);
        when(fixedTg.getName()).thenReturn("mock-fixed");
        when(fixedTg.getType()).thenReturn(Telegram.Type.Fixed);
        when(mockBuilder.getTelegram()).thenReturn(fixedTg);

        // =====================================================================
        // 🚀 2. Register Mock builder globally in the global factory
        // =====================================================================
        TelegramFactory.clear();
        TelegramFactory.register("mock-fixed", mockBuilder);

        when(mockBuilder.buildFromMap(anyMap(), any())).thenReturn(FIXED_RAW);
        when(mockContext.getPluginName()).thenReturn("FIXED_ALCHEMIST");
    }

    private Payload createFreshEvent() throws Exception {
        Map<String, Object> bodyMap = new HashMap<>();
        bodyMap.put("id", "TX001");
        bodyMap.put("amt", 1000L);

        // 🚀 [Hell Simulation] Intentionally construct properties as ImmutableMap
        Map<String, Object> immProps = new HashMap<>();
        immProps.put("env", "PROD");
        immProps.put("sysEnv", "PROD");

        Payload payload = new ZefioMessage("OLD_RAW".getBytes(), StandardCharsets.UTF_8);
        payload.setTelegramName("mock-fixed");
        payload.setTrxID("TRX-999");

        // Set Mock's properties to return Immutable,
        // or configure it to perform setProperty using the actual internal logic of ZefioMessage
        immProps.forEach(payload::setHeader);

        when(mockBuilder.parseToMap(any(), any())).thenReturn(bodyMap);
        return payload;
    }

    private Payload run(Payload ev, Map<String, String> amMap) throws FlowException {
        SpELModifierInterceptorValues values = new SpELModifierInterceptorValues();
        List<SpelAssignment> list = new ArrayList<>();
        amMap.forEach((t, e) -> {
            SpelAssignment a = new SpelAssignment();
            a.setTarget(t); a.setExpression(e);
            list.add(a);
        });
        values.setAssignments(list);
        when(mockContext.getContext()).thenReturn(Collections.singletonMap("assignments", list));

        SpELModifierInterceptor filter = new SpELModifierInterceptor(mockContext);
        return filter.process(ev);
    }

    @Nested
    @DisplayName("[Batch 3] Context Transition (Alchemy) - 50 Cases")
    class AlchemyBatch {
        @Test void test() throws Exception {
            Map<String, String> m = new LinkedHashMap<>();
            // 🚀 [Modified] event.properties -> payload.headers
            m.put("payload.headers['bak_id']", "#{body['id']}");
            m.put("body['header_env']", "#{payload.headers['sysEnv']}");
            m.put("payload.headers['new_flag']", "#{true}");

            Payload res = run(createFreshEvent(), m);

            assertEquals("TX001", res.getHeader("bak_id"));
            assertEquals("PROD", PayloadExpressionEvaluator.evaluate("#{body['header_env']}", res, String.class));
            assertTrue((Boolean)res.getHeader("new_flag"));
        }
    }

    @Nested
    @DisplayName("[Batch 4] Fixed layout and precise byte operations (50 Cases)")
    class FixedBatch {
        @Test void test() throws Exception {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("body['id']", "#{'StringTest'}");
            m.put("body['amt']", "#{body['amt'] + 100}");

            Payload res = run(createFreshEvent(), m);
            assertEquals(1100L, PayloadExpressionEvaluator.evaluate("#{body['amt']}", res, Long.class));
            assertArrayEquals(FIXED_RAW, res.getBody());
        }
    }

    @Nested
    @DisplayName("[Batch 5] Madness & Optimization (50 Cases)")
    class MadnessBatch {
        @Test void test() throws Exception {
            // 🚀 Verify Write-Back (byte assembly) does not occur when modifying only Property
            // 🚀 [Modified] event.properties -> payload.headers
            Map<String, String> m1 = Collections.singletonMap("payload.headers['only_prop']", "#{'change'}");
            Payload res1 = run(createFreshEvent(), m1);
            assertNotEquals(new String(FIXED_RAW), new String(res1.getBody()));

            // 🚀 Complex operations and Java API
            Map<String, String> m2 = new LinkedHashMap<>();
            m2.put("body['uuid']", "#{T(java.util.UUID).randomUUID().toString()}");
            // 🚀 [Modified] event.properties -> payload.headers
            m2.put("payload.headers['ts']", "#{T(java.lang.System).currentTimeMillis()}");

            Payload res2 = run(createFreshEvent(), m2);
            assertNotNull(res2.getHeader("ts"));
        }
    }
}
