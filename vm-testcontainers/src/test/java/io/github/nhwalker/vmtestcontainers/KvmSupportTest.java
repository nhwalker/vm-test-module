package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KvmSupportTest {

    @Test
    void missingDeviceIsReported(@TempDir Path dir) {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        assertThat(KvmSupport.kvmProblem(dir.resolve("kvm"))).hasValueSatisfying(msg ->
                assertThat(msg).contains("does not exist").contains("withSoftwareEmulation"));
    }

    @Test
    void unreadableDeviceIsReported(@TempDir Path dir) throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root can read anything");
        Path fake = dir.resolve("kvm");
        Files.createFile(fake);
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("---------"));
        assertThat(KvmSupport.kvmProblem(fake)).hasValueSatisfying(msg ->
                assertThat(msg).contains("not readable/writable").contains("usermod -aG kvm"));
    }

    @Test
    void usableDeviceHasNoProblem(@TempDir Path dir) throws Exception {
        assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        Path fake = dir.resolve("kvm");
        Files.createFile(fake);
        assertThat(KvmSupport.kvmProblem(fake)).isEmpty();
    }

    @Test
    void remoteDockerHostsAreDetected() {
        assertThat(KvmSupport.remoteDockerProblem(null)).isEmpty();
        assertThat(KvmSupport.remoteDockerProblem("")).isEmpty();
        assertThat(KvmSupport.remoteDockerProblem("unix:///var/run/docker.sock")).isEmpty();
        assertThat(KvmSupport.remoteDockerProblem("tcp://10.0.0.5:2376")).isPresent();
        assertThat(KvmSupport.remoteDockerProblem("ssh://ci@build-host")).isPresent();
    }
}
