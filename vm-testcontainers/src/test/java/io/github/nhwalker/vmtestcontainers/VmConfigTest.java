package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class VmConfigTest {

    private static final Path IMG = Path.of("/images/rocky.qcow2");

    @Test
    void defaultsMapToRunnerEnvironment() {
        VmConfig c = new VmConfig(IMG, 2, 2048, null, Set.of(), false, null, null);
        Map<String, String> env = c.toEnvironment();
        assertThat(env).containsEntry("VM_IMAGE", "/images/base.qcow2")
                .containsEntry("VM_CPUS", "2")
                .containsEntry("VM_MEM_MB", "2048")
                .containsEntry("VM_ACCEL", "kvm")
                .containsEntry("VM_PORTS", "")
                .containsEntry("VM_SUBNET", "10.200.0.0/24")
                .containsEntry("VM_SEED_DIR", "/vm/seed")
                .doesNotContainKeys("VM_DISK_GB", "VM_QEMU_EXTRA_ARGS");
    }

    @Test
    void portsAreSortedDeduplicatedAndExcludeSsh() {
        VmConfig c = new VmConfig(IMG, 4, 4096, 20, new java.util.HashSet<>(java.util.List.of(8080, 22, 443, 8080)), true, "10.9.9.0/24", "-cpu qemu64");
        Map<String, String> env = c.toEnvironment();
        assertThat(env).containsEntry("VM_PORTS", "443,8080")
                .containsEntry("VM_DISK_GB", "20")
                .containsEntry("VM_ACCEL", "tcg")
                .containsEntry("VM_SUBNET", "10.9.9.0/24")
                .containsEntry("VM_QEMU_EXTRA_ARGS", "-cpu qemu64");
        assertThat(c.vmPorts()).containsExactly(22, 443, 8080);
    }

    @Test
    void invalidValuesAreRejected() {
        assertThatThrownBy(() -> new VmConfig(IMG, 0, 2048, null, Set.of(), false, null, null))
                .hasMessageContaining("cpus");
        assertThatThrownBy(() -> new VmConfig(IMG, 1, 100, null, Set.of(), false, null, null))
                .hasMessageContaining("memoryMb");
        assertThatThrownBy(() -> new VmConfig(IMG, 1, 2048, 0, Set.of(), false, null, null))
                .hasMessageContaining("diskGb");
        assertThatThrownBy(() -> new VmConfig(IMG, 1, 2048, null, Set.of(70000), false, null, null))
                .hasMessageContaining("port");
        assertThatThrownBy(() -> new VmConfig(IMG, 1, 2048, null, Set.of(), false, "10.0.0.0/16", null))
                .hasMessageContaining("subnet");
        assertThatThrownBy(() -> new VmConfig(null, 1, 2048, null, Set.of(), false, null, null))
                .hasMessageContaining("image");
    }
}
