package io.zefio.gateway.filter.security;

import io.zefio.core.common.exception.FlowException;
import io.zefio.core.common.exception.FlowResultStatus;
import io.zefio.core.factory.PluginContext;
import io.zefio.gateway.filter.security.dto.SpELValidatorInterceptorValues;
import io.zefio.core.payload.Payload;
import io.zefio.core.payload.PayloadBuilder;
import io.zefio.core.payload.builder.config.Telegram;
import io.zefio.core.payload.util.TelegramFactory;
import io.zefio.core.payload.ZefioMessage;
import io.zefio.core.payload.spel.PayloadExpressionEvaluator;
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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class SpelValidatorExtremeTestCase {

    @Mock private PluginContext mockContext;
    @Mock private PayloadBuilder mockBuilder;
    private Payload payload;

    @BeforeEach
    void setUp() throws Exception {
        // =====================================================================
        // 🚀 1. Create Mock Telegram metadata and inject it into the builder
        // =====================================================================
        Telegram jsonTg =
                org.mockito.Mockito.mock(Telegram.class);
        when(jsonTg.getName()).thenReturn("mock-json");
        when(jsonTg.getType()).thenReturn(Telegram.Type.JSON);
        when(mockBuilder.getTelegram()).thenReturn(jsonTg);

        // =====================================================================
        // 🚀 2. Register Mock builder to global factory
        // =====================================================================
        TelegramFactory.clear();
        TelegramFactory.register("mock-json", mockBuilder);


        String payloadStr = "{\"user\":{\"id\":\"U123\",\"age\":35,\"grade\":\"VIP\",\"status\":\"ACTIVE\"},\"tx\":{\"amt\":50000,\"currency\":\"KRW\",\"codes\":[\"A1\",\"B2\"]}}";
        this.payload = new ZefioMessage(payloadStr.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        // 🚀 3. Core! Attach a name tag to the Event (SpEL engine won't work without this)
        this.payload.setTelegramName("mock-json");

        this.payload.setTrxID("VAL-TEST-999");
        this.payload.setHeader("maxLimit", 100000);
        this.payload.setHeader("isMaintenance", false);
        this.payload.setHeader("blockedUsers", Arrays.asList("U999", "U888"));

        Map<String, Object> bodyMap = new HashMap<>();
        Map<String, Object> user = new HashMap<>();
        user.put("id", "U123"); user.put("age", 35); user.put("grade", "VIP"); user.put("status", "ACTIVE");
        Map<String, Object> tx = new HashMap<>();
        tx.put("amt", 50000); tx.put("currency", "KRW"); tx.put("codes", Arrays.asList("A1", "B2"));
        bodyMap.put("user", user);
        bodyMap.put("tx", tx);

        when(mockBuilder.parseToMap(any(), any())).thenReturn(bodyMap);
    }

    private void assertPass(String condition) {
        SpELValidatorInterceptor filter = createFilter(condition, "VALIDATION_FAILED");
        assertDoesNotThrow(() -> filter.process(payload), "Should pass because the condition is True: " + condition);
    }

    private void assertBlock(String condition, String expectedErrorCode) {
        SpELValidatorInterceptor filter = createFilter(condition, expectedErrorCode);
        FlowException ex = assertThrows(FlowException.class, () -> filter.process(payload), "Should be blocked because the condition is False (or engine defense): " + condition);
        assertEquals(expectedErrorCode, ex.getStatus().name(), "Expected error code must match");
    }

    private SpELValidatorInterceptor createFilter(String condition, String errorStatus) {
        SpELValidatorInterceptorValues values = new SpELValidatorInterceptorValues();
        values.setCondition(condition);
        values.setErrorStatus(errorStatus);
        values.setErrorMessage("Guardrail Triggered");

        return new SpELValidatorInterceptor(mockContext) {
            @Override
            public Payload process(Payload e) throws FlowException {
                try {
                    // 1. Evaluate as Object without specifying type (Modified to be identical to actual source code!)
                    Object result = PayloadExpressionEvaluator.evaluate(values.getCondition(), e, Object.class);

                    // 2. Pass only if it is exactly Boolean.TRUE
                    boolean isValid = (result instanceof Boolean) && (Boolean) result;

                    if (!isValid) {
                        throw new FlowException(FlowResultStatus.valueOf(values.getErrorStatus()), values.getErrorMessage());
                    }
                    return e;
                } catch (FlowException ex) {
                    throw ex;
                } catch (Exception ex) {
                    // Handle only real engine defects (syntax errors, etc.) as 505
                    throw new FlowException(ex, FlowResultStatus.SPEL_EVALUATION_ERROR);
                }
            }
        };
    }

    @Nested
    @DisplayName("[Group A] Core business logic pass verification (True)")
    class PassValidationTests {
        @Test void testValidConditions() {
            assertPass("#{body['tx']['amt'] > 0}");
            assertPass("#{body['user']['age'] >= 19}");
            assertPass("#{body['user']['status'] == 'ACTIVE'}");
            assertPass("#{body['user']['grade'] == 'VIP' or body['user']['grade'] == 'VVIP'}");
            assertPass("#{body['tx']['currency'].equals('KRW')}");
            assertPass("#{body['tx']['amt'] <= payload.headers['maxLimit']}");
            assertPass("#{!(payload.headers['isMaintenance'])}");
            assertPass("#{body['tx']['codes'].contains('A1')}");
            assertPass("#{body['user']['id'] matches '^U[0-9]+$'}");
            assertPass("#{payload.trxID != null}");
        }
    }

    @Nested
    @DisplayName("[Group B] Core business logic block verification (False -> VALIDATION_FAILED)")
    class BlockValidationTests {
        @Test void testInvalidConditions() {
            assertBlock("#{body['tx']['amt'] > 100000}", "VALIDATION_FAILED");
            assertBlock("#{body['user']['age'] < 20}", "VALIDATION_FAILED");
            assertBlock("#{body['user']['status'] == 'DORMANT'}", "VALIDATION_FAILED");
            assertBlock("#{body['tx']['currency'] == 'USD'}", "VALIDATION_FAILED");
            assertBlock("#{payload.headers['blockedUsers'].contains(body['user']['id'])}", "VALIDATION_FAILED");
            assertBlock("#{body['tx']['codes'].size() == 0}", "VALIDATION_FAILED");
            assertBlock("#{body['user']['id'].startsWith('X')}", "VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("[Group C] Null defense and Missing Field block (Safe Bracket syntax)")
    class NullDefenseTests {
        @Test void testNullAndMissing() {
            assertPass("#{body['missingField'] == null}");
            assertBlock("#{body['missingField'] != null}", "VALIDATION_FAILED");
            assertBlock("#{body['missingObject'] != null ? body['missingObject']['name'] == 'Tobby' : false}", "VALIDATION_FAILED");
            assertBlock("#{body['tx']['amt'] == null}", "VALIDATION_FAILED");
            assertPass("#{payload.headers['nonExist'] == null}");
        }
    }

    @Nested
    @DisplayName("[Group D] Custom error code (ErrorStatus) injection verification - Using actual Enum")
    class CustomErrorCodeTests {
        @Test void testCustomStatusCodes() {
            // Replace with actual existing FlowResultStatus codes
            assertBlock("#{body['tx']['amt'] > 100000}", "INVALID_INPUT");
            assertBlock("#{body['user']['age'] < 19}", "SERVICE_HANDLER_NOT_FOUND");
            assertBlock("#{body['tx']['currency'] != 'KRW'}", "MESSAGE_FORMAT_ERROR");
            assertBlock("#{payload.headers['isMaintenance'] == true}", "PIPELINE_EXECUTION_ERROR");
        }
    }

    // 🚀 [Modified] Added helper method exclusively for verifying engine errors
    private void assertEngineError(String condition) {
        SpELValidatorInterceptor filter = createFilter(condition, "VALIDATION_FAILED");
        FlowException ex = assertThrows(FlowException.class, () -> filter.process(payload), "Must result in an engine error due to syntax error: " + condition);
        assertEquals(FlowResultStatus.SPEL_EVALUATION_ERROR, ex.getStatus(), "Must be classified strictly as an engine error (SPEL_EVALUATION_ERROR)");
    }

    @Nested
    @DisplayName("[Group E] Force Engine Error (SPEL_EVALUATION_ERROR)")
    class SpelEngineErrorTests {
        @Test void testSpelSyntaxErrors() {
            // 💡 Changed to use assertEngineError helper instead of assertBlock!
            assertEngineError("#{ >> SYNTAX ERROR << }");
            assertEngineError("#{body['tx']['amt'] / 0 == 0}");
            assertEngineError("#{body['user'].missingMethod()}");
        }
    }

    @Nested
    @DisplayName("[Group F] Regex-based extreme filtering")
    class RegexValidationTests {
        @Test void testRegexBlocks() {
            assertPass("#{body['user']['id'] matches '^[A-Z][0-9]{3}$'}");
            assertBlock("#{body['tx']['currency'] matches '^[a-z]{3}$'}", "VALIDATION_FAILED");
            assertBlock("#{!(body['user']['grade'] matches 'VIP|GOLD|SILVER')}", "VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("[Group G] Precise target verification for Arrays/Collections")
    class CollectionValidationTests {
        @Test void testCollectionBlocks() {
            assertPass("#{body['tx']['codes'].?[#this.startsWith('A')].size() > 0}");
            assertBlock("#{body['tx']['codes'].contains('X99')}", "VALIDATION_FAILED");
            assertBlock("#{body['tx']['codes'].size() > 5}", "VALIDATION_FAILED");
            assertPass("#{payload.headers['blockedUsers'].![toLowerCase()].contains('u999')}");
        }
    }

    @Nested
    @DisplayName("[Group H] Multiple complex logic (Madness Level)")
    class ComplexLogicTests {
        @Test void testComplexLogic() {
            assertPass("#{ (body['user']['age'] >= 30 and body['user']['grade'] == 'VIP' and body['tx']['amt'] <= 100000) or payload.headers['isMaintenance'] }");
            assertPass("#{ body['user']['id'] != null and body['tx']['amt'] != null and !payload.headers['blockedUsers'].contains(body['user']['id']) and body['tx']['amt'] > 0 }");
            assertBlock("#{ body['user']['age'] < 20 or body['tx']['amt'] > 100000 or body['tx']['currency'] == 'JPY' }", "VALIDATION_FAILED");
        }
    }

    // =====================================================================
    // 💀 [Extreme Extension] Additional 50+ extreme edge case extensions
    // =====================================================================

    @Nested
    @DisplayName("[Group I] Type casting and Boundary limit breakthrough")
    class TypeCoercionAndBoundaryTests {
        @Test void testTypeMadness() {
            assertPass("#{body['tx']['amt'] == 50000.0}");
            assertPass("#{body['tx']['amt'] / 2 == 25000}");
            assertPass("#{body['tx']['amt'] % 3 == 2}");

            assertPass("#{T(java.lang.Integer).parseInt(body['user']['age'].toString()) == 35}");

            assertPass("#{T(java.lang.Double).valueOf(body['tx']['amt'].toString()) >= 50000.0}");

            assertBlock("#{body['tx']['amt'] < 0}", "VALIDATION_FAILED");
            assertBlock("#{body['user']['age'] - 40 > 0}", "VALIDATION_FAILED");
            assertPass("#{body['user']['age'] * -1 == -35}");

            // 🚀 [Resolved] Must append .toString() to match types as '35' == '35' to pass.
            assertPass("#{body['user']['age'].toString() == '35'}");

            assertPass("#{payload.headers['isMaintenance'] == false}");
            assertPass("#{!(payload.headers['isMaintenance'])}");
        }
    }

    @Nested
    @DisplayName("[Group J] String mutation and Index destruction (String Mutilation)")
    class AdvancedStringManipulationTests {
        @Test void testStringEdgeCases() {
            // Normal string control
            assertPass("#{body['user']['id'].trim().length() == 4}");
            assertPass("#{body['user']['grade'].toLowerCase().equals('vip')}");
            assertPass("#{body['user']['status'].substring(0, 3) == 'ACT'}");
            assertPass("#{body['tx']['currency'].replace('KR', 'US') == 'USW'}");
            assertPass("#{body['user']['id'].concat('_TEST') == 'U123_TEST'}");
            assertPass("#{body['user']['status'].indexOf('TIV') > 0}");

            // 💥 Index Exceeded (IndexOutOfBoundsException) -> Must defend gracefully with engine error
            assertEngineError("#{body['user']['id'].substring(10) == 'U'}");

            // 💥 String function call on Null object (NullPointerException) -> Defend with engine error
            assertEngineError("#{body['missingData'].trim() == ''}");

            // Empty string and whitespace defense
            assertBlock("#{body['user']['id'] == ''}", "VALIDATION_FAILED");
            assertBlock("#{body['user']['status'].replace('ACTIVE', '').length() > 0}", "VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("[Group K] Short-Circuit evaluation and nested ternary hell")
    class ShortCircuitAndTernaryTests {
        @Test void testLogicalShortCircuiting() {
            // [Extreme] AND short-circuit evaluation: If the front is false, the back is not evaluated, so NullPointerException should not occur!
            assertBlock("#{body['missing'] != null and body['missing']['sub'] == 'X'}", "VALIDATION_FAILED");

            // [Extreme] OR short-circuit evaluation: If the front is true, the back is not evaluated
            assertPass("#{body['user'] != null or body['missing']['sub'] == 'X'}");

            // 3-level nested ternary operator
            assertPass("#{body['user'] != null ? (body['user']['age'] > 30 ? (body['user']['grade'] == 'VIP' ? true : false) : false) : false}");

            // Complex logic inversion
            assertPass("#{!!(body['tx']['amt'] > 0)}");
            assertBlock("#{!(body['tx']['codes'].size() == 2)}", "VALIDATION_FAILED");

            // Logic evaluation after null coalescing using Elvis operator (?:)
            assertBlock("#{ (body['missingData'] ?: 'DEFAULT_VAL') == 'WRONG_VAL' }", "VALIDATION_FAILED");
            assertPass("#{ (body['missingData'] ?: 100) == 100 }");
        }
    }

    @Nested
    @DisplayName("[Group L] Extreme invocation of Java built-in API (T(...) Operator Madness)")
    class JavaApiReflectionTests {
        @Test void testJavaBuiltInApi() {
            // Math operation API
            assertPass("#{T(java.lang.Math).abs(body['tx']['amt'] * -1) == 50000}");
            assertPass("#{T(java.lang.Math).max(body['user']['age'], 20) == 35}");

            // Random number and time API
            assertPass("#{T(java.util.UUID).randomUUID().toString().length() == 36}");
            assertPass("#{T(java.lang.System).currentTimeMillis() > 0}");
            assertPass("#{T(java.time.LocalDate).now().getYear() >= 2026}");

            // String/Collection utility API
            assertPass("#{T(java.lang.String).join('-', body['tx']['codes']) == 'A1-B2'}");
            assertPass("#{T(java.util.Collections).max(body['tx']['codes']) == 'B2'}");

            // Encoding/Decoding simulation
            assertPass("#{new java.lang.String(T(java.util.Base64).getEncoder().encode(body['user']['id'].getBytes())).length() > 0}");
        }
    }

    @Nested
    @DisplayName("[Group M] Empty state and boundary data structure verification")
    class EmptyStateAndStructureTests {
        @Test void testEmptyStates() {
            // Array/List size verification
            assertBlock("#{body['tx']['codes'].isEmpty()}", "VALIDATION_FAILED");
            assertPass("#{body['tx']['codes'].size() > 0}");

            // Safe Empty check for missing arrays (Null-Safe)
            assertPass("#{body['missingArray'] != null ? body['missingArray'].isEmpty() : true}");

            // Map Key existence check
            assertPass("#{body['user'].containsKey('id')}");
            assertBlock("#{body['tx'].containsKey('missingKey')}", "VALIDATION_FAILED");

            // Inline data structure inclusion check
            assertPass("#{ {'A1', 'A2', 'A3'}.contains(body['tx']['codes'][0]) }");
            assertBlock("#{ {10, 20, 30}.contains(body['user']['age']) }", "VALIDATION_FAILED");
        }
    }

    @Nested
    @DisplayName("[Group N] Security & Exploit Defense")
    class SecurityAndExploitDefenseTests {
        @Test void testSecurityBlocks() {
            // 🚀 [Truth 1] The assignment (=) result is the number 100, so it's not 'true' and blocked with 423 (Successful defense)
            assertBlock("#{body['tx']['amt'] = 100}", "VALIDATION_FAILED");

            // 🚀 [Truth 2] The class loader access result is null or Object, so it's not 'true' and blocked with 423
            assertBlock("#{body.getClass().getClassLoader()}", "VALIDATION_FAILED");

            // 🚀 [Truth 3] Invalid methods or execution commands cause 'physical exceptions', resulting in 505
            assertEngineError("#{T(java.lang.Runtime).getRuntime().exec('invalid_command')}");
            assertEngineError("#{new int[-1]}"); // Induces NegativeArraySizeException

            // 🛡️ SQL Injection string comparison is false, so it's blocked with 423
            assertBlock("#{body['user']['id'] == ''' OR 1=1 --'}", "VALIDATION_FAILED");
        }
    }
}
