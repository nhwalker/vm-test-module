package io.github.nhwalker.vmtestcontainers;

/**
 * Result of a command executed inside the guest over SSH. Mirrors Testcontainers'
 * {@code Container.ExecResult}, whose constructor is not accessible outside its package.
 *
 * @param exitCode process exit status, or {@code -1} if the remote did not report one
 * @param stdout   captured standard output
 * @param stderr   captured standard error
 */
public record VmExecResult(int exitCode, String stdout, String stderr) {

    public boolean succeeded() {
        return exitCode == 0;
    }

    /** Throws if the exit code is non-zero, with stdout/stderr in the message. */
    public VmExecResult expectSuccess() {
        if (!succeeded()) {
            throw new IllegalStateException("command failed with exit code " + exitCode
                    + "\n--- stdout ---\n" + stdout + "\n--- stderr ---\n" + stderr);
        }
        return this;
    }
}
