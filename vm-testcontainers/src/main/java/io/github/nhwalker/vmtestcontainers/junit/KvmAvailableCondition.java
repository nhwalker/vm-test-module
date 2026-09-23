package io.github.nhwalker.vmtestcontainers.junit;

import io.github.nhwalker.vmtestcontainers.KvmSupport;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Implements {@link EnabledIfKvmAvailable}. */
public class KvmAvailableCondition implements ExecutionCondition {

    public static final String SOFTWARE_EMULATION_PROPERTY = "vm.testcontainers.software-emulation";

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (Boolean.getBoolean(SOFTWARE_EMULATION_PROPERTY)) {
            return ConditionEvaluationResult.enabled("software emulation opted in via -D" + SOFTWARE_EMULATION_PROPERTY);
        }
        return KvmSupport.kvmProblem()
                .map(problem -> ConditionEvaluationResult.disabled("KVM not available: " + problem))
                .orElseGet(() -> ConditionEvaluationResult.enabled("/dev/kvm is usable"));
    }
}
