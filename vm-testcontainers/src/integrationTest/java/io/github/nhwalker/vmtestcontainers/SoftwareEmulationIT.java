package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Boots without KVM. Very slow (several minutes); opt in with {@code -Dvm.test.tcg=true}.
 */
@Tag("slow")
@EnabledIfSystemProperty(named = "vm.test.tcg", matches = "true")
class SoftwareEmulationIT {

    static final Path IMAGE = Path.of(System.getProperty("vm.test.image", "/nonexistent"));

    @Test
    void bootsUnderTcg() {
        assumeTrue(Files.isRegularFile(IMAGE), "set VM_TEST_IMAGE to a Rocky 9 GenericCloud qcow2");
        try (VmContainer vm = new VmContainer(IMAGE)
                .withSoftwareEmulation(true)
                .withCpus(2)
                .withStartupTimeout(Duration.ofMinutes(30))) {
            vm.start();
            assertThat(vm.execInVm("uname", "-r").expectSuccess().stdout()).contains("el9");
        }
    }
}
