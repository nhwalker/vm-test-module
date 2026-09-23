package io.github.nhwalker.vmtestcontainers;

import java.util.Objects;
import java.util.UUID;

/**
 * Renders the cloud-init NoCloud seed: {@code meta-data} and {@code user-data}.
 *
 * <p>The module always contributes a {@code #cloud-config} part that creates the SSH user with passwordless
 * sudo and the generated public key. If the caller supplied extra user-data (a {@code #cloud-config}
 * document or a {@code #!} shell script), the two are combined into a multipart MIME message. The
 * module's part is placed last and carries a {@code merge_how} directive so that lists such as
 * {@code users} are appended to, not replaced, when both parts define them.
 */
public final class CloudInitSeed {

    static final String MERGE_HOW = "list(append)+dict(no_replace,recurse_list)+str()";
    private static final String BOUNDARY = "==vm-testcontainers-boundary==";

    private final String instanceId;
    private final String hostname;
    private final String sshUser;
    private final String publicKeyLine;
    private final String callerUserData;

    public CloudInitSeed(String instanceId, String hostname, String sshUser, String publicKeyLine, String callerUserData) {
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.hostname = Objects.requireNonNull(hostname, "hostname");
        this.sshUser = validateUser(sshUser);
        this.publicKeyLine = Objects.requireNonNull(publicKeyLine, "publicKeyLine").strip();
        this.callerUserData = callerUserData == null || callerUserData.isBlank() ? null : callerUserData;
        if (this.publicKeyLine.contains("\n")) {
            throw new IllegalArgumentException("publicKeyLine must be a single line");
        }
    }

    public static String randomInstanceId() {
        return "vmtc-" + UUID.randomUUID();
    }

    public String metaData() {
        return "instance-id: " + instanceId + "\n"
                + "local-hostname: " + hostname + "\n";
    }

    /** The module's own cloud-config document. */
    public String moduleCloudConfig() {
        return "#cloud-config\n"
                + "merge_how: \"" + MERGE_HOW + "\"\n"
                + "ssh_pwauth: false\n"
                + "users:\n"
                + "  - default\n"
                + "  - name: " + sshUser + "\n"
                + "    gecos: vm-testcontainers ssh user\n"
                + "    shell: /bin/bash\n"
                + "    lock_passwd: true\n"
                + "    sudo: \"ALL=(ALL) NOPASSWD:ALL\"\n"
                + "    ssh_authorized_keys:\n"
                + "      - \"" + publicKeyLine + "\"\n";
    }

    /** Plain cloud-config when there is no caller user-data, multipart MIME otherwise. */
    public String userData() {
        if (callerUserData == null) {
            return moduleCloudConfig();
        }
        String callerType = contentTypeOf(callerUserData);
        StringBuilder sb = new StringBuilder();
        sb.append("Content-Type: multipart/mixed; boundary=\"").append(BOUNDARY).append("\"\n");
        sb.append("MIME-Version: 1.0\n\n");
        appendPart(sb, callerType, "caller-user-data", callerUserData);
        appendPart(sb, "text/cloud-config", "vm-testcontainers.cfg", moduleCloudConfig());
        sb.append("--").append(BOUNDARY).append("--\n");
        return sb.toString();
    }

    static String contentTypeOf(String userData) {
        String firstLine = userData.stripLeading().lines().findFirst().orElse("").strip();
        if (firstLine.startsWith("#cloud-config")) {
            return "text/cloud-config";
        }
        if (firstLine.startsWith("#!")) {
            return "text/x-shellscript";
        }
        if (firstLine.startsWith("#cloud-boothook")) {
            return "text/cloud-boothook";
        }
        throw new IllegalArgumentException("user-data must start with '#cloud-config', '#!' (shell script) or "
                + "'#cloud-boothook'; multipart input is not supported. First line was: " + firstLine);
    }

    private static void appendPart(StringBuilder sb, String contentType, String filename, String body) {
        sb.append("--").append(BOUNDARY).append('\n');
        sb.append("Content-Type: ").append(contentType).append("; charset=\"utf-8\"\n");
        sb.append("MIME-Version: 1.0\n");
        sb.append("Content-Transfer-Encoding: 8bit\n");
        sb.append("Content-Disposition: attachment; filename=\"").append(filename).append("\"\n\n");
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
    }

    static String validateUser(String user) {
        Objects.requireNonNull(user, "sshUser");
        if (!user.matches("[a-z_][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid unix user name: " + user);
        }
        return user;
    }
}
