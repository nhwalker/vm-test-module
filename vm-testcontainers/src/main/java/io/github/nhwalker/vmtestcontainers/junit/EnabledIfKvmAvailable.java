package io.github.nhwalker.vmtestcontainers.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * JUnit 5 condition: the annotated test class or method runs only when {@code /dev/kvm} is usable on this
 * machine, or when system property {@code vm.testcontainers.software-emulation=true} opts into TCG.
 * Otherwise the tests are reported as skipped with the reason.
 *
 * <p>Requires {@code org.junit.jupiter:junit-jupiter-api} on the test classpath.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(KvmAvailableCondition.class)
public @interface EnabledIfKvmAvailable {
}
