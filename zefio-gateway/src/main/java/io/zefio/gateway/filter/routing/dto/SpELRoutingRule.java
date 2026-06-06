package io.zefio.gateway.filter.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.zefio.core.annotation.AIOpsTuning;
import io.zefio.core.payload.ExchangePattern;
import lombok.Data;
import java.util.Map;

/**
 * Configuration model for an individual SpEL routing rule.
 * Allows for dynamic branching based on payload content, infrastructure properties, or complex logic.
 * Upgraded to capture flattened endpoint metadata from the Control Plane seamlessly.
 */
@Data
@Schema(description = "Configuration for SpEL-based intelligent routing rules.")
public class SpELRoutingRule {

    @Schema(description = "Rule identifier for audit logs and debugging visibility.",
            example = "Bank_A_Routing_Rule")
    private String name;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.BUSINESS_LOGIC)
    @Schema(description = "The SpEL expression used to determine the branch.\n" +
            "1. **Content-based**: #{body.bankCode == '004'}\n" +
            "2. **Infrastructure-based**: #{payload.headers['http.req.path'].startsWith('/api/v1')}\n" +
            "3. **Complex Logic**: #{body.amount > 1000000 and payload.headers.vipStatus == 'Y'}",
            example = "#{body.TARGET_VAL == 'BANK_A'}",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String condition;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.PROTOCOL_SPEC)
    @Schema(description = "Overrides the communication pattern for the target module.\n" +
            "* **twoway**: Wait for a response (Default)\n" +
            "* **oneway**: Fire-and-Forget pattern",
            nullable = true,
            example = "twoway")
    private ExchangePattern exchangePattern;

    // ========================================================================
    // 💡 Extended Core Structural Properties for CP Flattening Compatibility
    // ========================================================================

    @Schema(description = "The resolved component plugin execution identifier type from CP.", example = "HttpUpstream")
    private String type;

    @Schema(description = "The mapped unique data serialization profile telegram key handle.", example = "json-standard")
    private String telegram;

    @Schema(description = "Dynamic inline Key-Value configuration parameters for JIT Netty channel instantiation.")
    private Map<String, Object> config;
}
