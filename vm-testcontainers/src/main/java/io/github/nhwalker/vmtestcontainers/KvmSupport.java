package io.github.nhwalker.vmtestcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Host preflight checks. The VM boots inside a container on the local Docker host, so the checks look
 * at this machine: {@code /dev/kvm} must exist and be usable, and the Docker daemon must be local
 * because the guest image is bind-mounted rather than copied.
 */
public final class KvmSupport {

    static final Path DEV_KVM = Path.of("/dev/kvm");

    private KvmSupport() {
    }

    /** True when {@code /dev/kvm} exists and is readable and writable by this process. */
    public static boolean isKvmAvailable() {
        return kvmProblem(DEV_KVM).isEmpty();
    }

    /** Human-readable reason KVM cannot be used, or empty if it can. */
    public static Optional<String> kvmProblem() {
        return kvmProblem(DEV_KVM);
    }

    static Optional<String> kvmProblem(Path devKvm) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return Optional.of("VM containers need a Linux Docker host with /dev/kvm; this is "
                    + System.getProperty("os.name") + ". Use withSoftwareEmulation(true) for slow TCG emulation.");
        }
        if (!Files.exists(devKvm)) {
            return Optional.of(devKvm + " does not exist. Enable virtualization (VT-x/AMD-V) in firmware, load the "
                    + "kvm_intel/kvm_amd module, or use withSoftwareEmulation(true) for slow TCG emulation.");
        }
        if (!Files.isReadable(devKvm) || !Files.isWritable(devKvm)) {
            return Optional.of(devKvm + " exists but is not readable/writable by user '"
                    + System.getProperty("user.name") + "'. Fix: sudo usermod -aG kvm $USER (then re-login), "
                    + "or on GitHub-hosted runners add a udev rule: "
                    + "KERNEL==\"kvm\", GROUP=\"kvm\", MODE=\"0666\", OPTIONS+=\"static_node=kvm\"");
        }
        return Optional.empty();
    }

    /**
     * The guest image is bind-mounted from this machine, so a remote Docker daemon cannot work.
     * Returns a problem description when {@code DOCKER_HOST} points at a TCP or SSH endpoint.
     */
    public static Optional<String> remoteDockerProblem() {
        return remoteDockerProblem(System.getenv("DOCKER_HOST"));
    }

    static Optional<String> remoteDockerProblem(String dockerHost) {
        if (dockerHost == null || dockerHost.isBlank()) {
            return Optional.empty();
        }
        String h = dockerHost.trim().toLowerCase();
        if (h.startsWith("unix://") || h.startsWith("npipe://")) {
            return Optional.empty();
        }
        return Optional.of("DOCKER_HOST=" + dockerHost + " points at a remote daemon. VmContainer bind-mounts the "
                + "guest image from this machine, so the Docker daemon must run locally.");
    }
}
