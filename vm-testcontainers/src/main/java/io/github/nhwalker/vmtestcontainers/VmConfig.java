package io.github.nhwalker.vmtestcontainers;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Immutable snapshot of the VM hardware/network settings, translated into the environment variables the
 * runner image's {@code vm-run.sh} understands. Kept separate from {@link VmContainer} so it can be unit
 * tested without Docker.
 *
 * @param image        host path of the base qcow2 (bind-mounted read-only)
 * @param cpus         number of vCPUs
 * @param memoryMb     guest RAM in MiB
 * @param diskGb       overlay size in GiB, or {@code null} to keep the image's virtual size
 * @param vmPorts      guest TCP ports to forward (22 is always forwarded)
 * @param softwareEmulation {@code true} to run QEMU with TCG instead of KVM
 * @param subnet       tap subnet for the guest, CIDR notation
 * @param extraQemuArgs extra arguments appended to the qemu command line, or {@code null}
 */
public record VmConfig(
        Path image,
        int cpus,
        int memoryMb,
        Integer diskGb,
        Set<Integer> vmPorts,
        boolean softwareEmulation,
        String subnet,
        String extraQemuArgs) {

    public static final int DEFAULT_CPUS = 2;
    public static final int DEFAULT_MEMORY_MB = 2048;
    public static final String DEFAULT_SUBNET = "10.200.0.0/24";

    /** Container-side path where the base image is bind-mounted. */
    public static final String IMAGE_MOUNT_PATH = "/images/base.qcow2";

    /** Container-side directory where cloud-init seed files are copied before start. */
    public static final String SEED_DIR = "/vm/seed";

    public VmConfig {
        if (image == null) {
            throw new IllegalArgumentException("image path is required");
        }
        if (cpus < 1) {
            throw new IllegalArgumentException("cpus must be >= 1, got " + cpus);
        }
        if (memoryMb < 256) {
            throw new IllegalArgumentException("memoryMb must be >= 256, got " + memoryMb);
        }
        if (diskGb != null && diskGb < 1) {
            throw new IllegalArgumentException("diskGb must be >= 1, got " + diskGb);
        }
        vmPorts = vmPorts == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(vmPorts));
        for (int p : vmPorts) {
            if (p < 1 || p > 65535) {
                throw new IllegalArgumentException("invalid VM port " + p);
            }
        }
        if (subnet == null || subnet.isBlank()) {
            subnet = DEFAULT_SUBNET;
        }
        if (!subnet.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.0/24")) {
            throw new IllegalArgumentException("subnet must be a /24 written as a.b.c.0/24, got " + subnet);
        }
    }

    /** Environment variables for the runner container. */
    public Map<String, String> toEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("VM_IMAGE", IMAGE_MOUNT_PATH);
        env.put("VM_CPUS", Integer.toString(cpus));
        env.put("VM_MEM_MB", Integer.toString(memoryMb));
        if (diskGb != null) {
            env.put("VM_DISK_GB", Integer.toString(diskGb));
        }
        env.put("VM_ACCEL", softwareEmulation ? "tcg" : "kvm");
        env.put("VM_PORTS", vmPorts.stream()
                .filter(p -> p != VmContainer.SSH_PORT)
                .map(String::valueOf)
                .collect(Collectors.joining(",")));
        env.put("VM_SUBNET", subnet);
        env.put("VM_SEED_DIR", SEED_DIR);
        if (extraQemuArgs != null && !extraQemuArgs.isBlank()) {
            env.put("VM_QEMU_EXTRA_ARGS", extraQemuArgs);
        }
        return env;
    }
}
