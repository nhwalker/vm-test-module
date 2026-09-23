package io.github.nhwalker.vmtestcontainers;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.wait.strategy.AbstractWaitStrategy;

/**
 * Default readiness check for {@link VmContainer}: the guest accepts SSH key authentication, and (when
 * the module manages cloud-init) {@code cloud-init status --wait} reports completion. Use
 * {@code waitingFor(...)} on the container to replace it.
 */
public class VmReadyWaitStrategy extends AbstractWaitStrategy {

    private static final Logger LOG = LoggerFactory.getLogger(VmReadyWaitStrategy.class);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private boolean waitForCloudInit = true;

    /** Skip the {@code cloud-init status --wait} step and only wait for SSH. */
    public VmReadyWaitStrategy withoutCloudInitCheck() {
        this.waitForCloudInit = false;
        return this;
    }

    @Override
    protected void waitUntilReady() {
        if (!(waitStrategyTarget instanceof VmContainer vm)) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used with VmContainer");
        }
        Instant deadline = Instant.now().plus(startupTimeout);
        waitForSsh(vm, deadline);
        if (waitForCloudInit && vm.isCloudInitManaged()) {
            waitForCloudInit(vm, deadline);
        }
        LOG.info("VM {} is ready (ssh on {}:{})", vm.getContainerName(), vm.getHost(), vm.getSshPort());
    }

    private void waitForSsh(VmContainer vm, Instant deadline) {
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            if (!vm.isRunning()) {
                throw new ContainerLaunchException("VM container exited before SSH came up. Container log tail:\n"
                        + tail(vm.getLogs(), 60));
            }
            try {
                VmExecResult r = vm.execInVmAsUser(Duration.ofSeconds(15), "true");
                if (r.succeeded()) {
                    return;
                }
                last = new IllegalStateException("probe command exited with " + r.exitCode());
            } catch (Exception e) {
                last = e;
                LOG.debug("ssh not ready yet: {}", e.toString());
            }
            sleep(POLL_INTERVAL);
        }
        throw new ContainerLaunchException("Timed out after " + startupTimeout + " waiting for SSH to the VM"
                + (last == null ? "" : " (last error: " + last + ")") + ". Serial console tail:\n"
                + tail(vm.getLogs(), 60), last);
    }

    private void waitForCloudInit(VmContainer vm, Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isNegative() || remaining.isZero()) {
            throw new ContainerLaunchException("Timed out after " + startupTimeout + " before cloud-init check could run");
        }
        VmExecResult r;
        try {
            r = vm.execInVm(remaining, "cloud-init", "status", "--wait");
        } catch (Exception e) {
            throw new ContainerLaunchException("cloud-init status check failed to run", e);
        }
        switch (r.exitCode()) {
            case 0 -> LOG.debug("cloud-init finished: {}", r.stdout().strip());
            case 2 -> LOG.warn("cloud-init finished with recoverable errors: {}\n{}", r.stdout().strip(), r.stderr().strip());
            default -> throw new ContainerLaunchException("cloud-init reported failure (exit " + r.exitCode()
                    + "):\n" + r.stdout() + r.stderr() + "\nSerial console tail:\n" + tail(vm.getLogs(), 60));
        }
    }

    static String tail(String text, int lines) {
        if (text == null || text.isEmpty()) {
            return "(no output)";
        }
        String[] all = text.split("\r?\n");
        int from = Math.max(0, all.length - lines);
        return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerLaunchException("interrupted while waiting for VM", e);
        }
    }
}
