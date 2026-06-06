package io.zefio.core.config.flow;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

/**
 * Defines conditional error handling logic based on the exception type.
 * Since the Control Plane (CP) performs full flattening, this configuration
 * directly contains the executable steps for error recovery without string references.
 */
@Data
public class ErrorHandlerConfiguration {

    /** The specific error type to handle (e.g., "TIMEOUT", "VALIDATION_FAILED", or "ANY"). */
    @JsonProperty("error-type")
    private String errorType = "ANY";

    /**
     * The sequence of steps to execute during error recovery.
     * CP has already resolved all global error templates into this explicit list.
     */
    private List<StepConfiguration> steps = new ArrayList<>();
}
