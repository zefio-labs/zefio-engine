package io.zefio.gateway.filter.routing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.zefio.core.annotation.AIOpsTuning;
import io.zefio.core.payload.ExchangePattern;
import lombok.Data;
import java.util.Map;

/**
 * Configuration model for an individual routing rule.
 * Supports offset-based extraction for Fixed formats and path-based extraction for JSON/XML.
 * Expanded to consume inline value-objects produced by the Control Plane's composite pass.
 */
@Data
@Schema(description = "Configuration for unified routing rules.")
public class MessageRoutingRule {

    @Schema(description = "The name of the rule for log identification.", example = "Emergency Logging")
    private String name;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.PROTOCOL_SPEC)
    @Schema(description = "[Fixed] The starting byte offset for data extraction.", example = "512")
    private Integer offset;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.PROTOCOL_SPEC)
    @Schema(description = "[Fixed] The length of data to extract from the offset.", example = "2")
    private Integer length;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.PROTOCOL_SPEC)
    @Schema(description = "[JSON/XML] Simple data extraction key for single-depth nodes.", example = "svc_id")
    private String key;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.PROTOCOL_SPEC)
    @Schema(description = "[JSON/XML] Precise extraction using XPath or JsonPath.", example = "//header/svc_id")
    private String path;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.BUSINESS_LOGIC)
    @Schema(description = "The value used for comparison against the extracted data.", example = "EM")
    private String matchValue;

    @AIOpsTuning(hotDeployable = false, riskLevel = AIOpsTuning.RiskLevel.CRITICAL, category = AIOpsTuning.Category.PROTOCOL_SPEC)
    @Schema(description = "The exchange pattern for the target module (oneway or twoway).", nullable = true, example = "oneway")
    private ExchangePattern exchangePattern;

    // ========================================================================
    // 💡 Extended Core Structural Properties for CP Flattening Compatibility
    // ========================================================================

    @Schema(description = "The resolved component plugin execution identifier type from CP.", example = "HttpUpstream")
    private String type;

    @Schema(description = "The mapped unique data serialization profile telegram key handle.", example = "xml-tcp-delimiter")
    private String telegram;

    @Schema(description = "Dynamic inline Key-Value configuration parameters for JIT Netty channel instantiation.")
    private Map<String, Object> config;
}
