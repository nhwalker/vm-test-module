package io.github.nhwalker.vmtestcontainers;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Device;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.SELContext;
import com.github.dockerjava.api.model.Volume;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * A Testcontainers container that boots a Linux virtual machine (Rocky 9, RHEL 9 or any other cloud
 * image) under QEMU/KVM and exposes it over SSH.
 *
 * <pre>{@code
 * try (VmContainer vm = new VmContainer(Path.of("/opt/images/Rocky-9-GenericCloud-Base.latest.x86_64.qcow2"))
 *         .withVmExposedPorts(8080)
 *         .withUserData("#cloud-config\npackages: [nginx]\n")) {
 *     vm.start();
 *     vm.execInVm("systemctl", "is-active", "nginx").expectSuccess();
 *     int port = vm.getMappedPort(8080);
 * }
 * }</pre>
 *
 * <p>Host requirements: Linux, a local Docker daemon, and a usable {@code /dev/kvm} (or
 * {@link #withSoftwareEmulation(boolean)} for slow TCG emulation).
 */
public class VmContainer extends GenericContainer<VmContainer> {

    private static final Logger LOG = LoggerFactory.getLogger(VmContainer.class);

    public static final String DEFAULT_RUNNER_IMAGE = "ghcr.io/nhwalker/vm-testcontainers-qemu";
    public static final String RUNNER_IMAGE_PROPERTY = "vm.testcontainers.runner-image";
    public static final String DEFAULT_SSH_USER = "tc";
    public static final int SSH_PORT = 22;
    public static final Duration DEFAULT_STARTUP_TIMEOUT = Duration.ofMinutes(5);

    private final Path image;
    private int cpus = VmConfig.DEFAULT_CPUS;
    private int memoryMb = VmConfig.DEFAULT_MEMORY_MB;
    private Integer diskGb;
    private final Set<Integer> vmPorts = new LinkedHashSet<>();
    private boolean softwareEmulation;
    private String subnet = VmConfig.DEFAULT_SUBNET;
    private String extraQemuArgs;
    private String userData;
    private String hostname;
    private boolean relabelImageForSelinux = true;

    private boolean cloudInitManaged = true;
    private String sshUser = DEFAULT_SSH_USER;
    private SshKeyPair keyPair;
    private Path privateKeyFile;

    private Duration execTimeout = Duration.ofMinutes(5);
    private Duration sshConnectTimeout = Duration.ofSeconds(10);
    private Duration shutdownTimeout = Duration.ofSeconds(30);

    private boolean hostConfigModifierInstalled;
    private volatile VmSsh ssh;

    /** Boots {@code image} with the runner image matching this library's version. */
    public VmContainer(Path image) {
        this(defaultRunnerImage(), image);
    }

    /** Boots {@code image} with an explicit runner image (for testing a locally built runner). */
    public VmContainer(DockerImageName runnerImage, Path image) {
        super(runnerImage);
        this.image = Objects.requireNonNull(image, "image").toAbsolutePath();
        waitingFor(new VmReadyWaitStrategy());
        withStartupTimeout(DEFAULT_STARTUP_TIMEOUT);
    }

    // ------------------------------------------------------------------ configuration

    public VmContainer withCpus(int cpus) {
        this.cpus = cpus;
        return self();
    }

    /** Guest RAM in MiB. */
    public VmContainer withMemory(int memoryMb) {
        this.memoryMb = memoryMb;
        return self();
    }

    /** Grows the (copy-on-write) root disk to this many GiB; cloud-init's growpart expands the filesystem. */
    public VmContainer withDiskSize(int gigabytes) {
        this.diskGb = gigabytes;
        return self();
    }

    /**
     * Guest TCP ports to expose. Each port is reachable from the test via {@link #getMappedPort(int)} and
     * from other containers on the same Docker network via this container's network alias. Port 22 is
     * always forwarded.
     */
    public VmContainer withVmExposedPorts(Integer... ports) {
        vmPorts.addAll(Arrays.asList(ports));
        return self();
    }

    /** Run QEMU without KVM. Boots take minutes instead of seconds; use only when KVM is unavailable. */
    public VmContainer withSoftwareEmulation(boolean enabled) {
        this.softwareEmulation = enabled;
        return self();
    }

    /** Private /24 for the guest NIC (default {@value VmConfig#DEFAULT_SUBNET}). Change if it collides with something the guest must reach. */
    public VmContainer withGuestSubnet(String cidr) {
        this.subnet = cidr;
        return self();
    }

    /** Extra arguments appended verbatim to the qemu command line. Advanced. */
    public VmContainer withExtraQemuArgs(String args) {
        this.extraQemuArgs = args;
        return self();
    }

    /**
     * Additional cloud-init user-data: a {@code #cloud-config} document, a {@code #!} shell script or a
     * {@code #cloud-boothook}. It is merged with the module's own cloud-config (which creates the SSH
     * user); lists such as {@code users} or {@code packages} are appended, not replaced.
     */
    public VmContainer withUserData(String userData) {
        this.userData = userData;
        return self();
    }

    /** Guest hostname set through cloud-init meta-data. Default: a generated {@code vm-xxxxxxxx}. */
    public VmContainer withHostname(String hostname) {
        this.hostname = hostname;
        return self();
    }

    /** Name of the SSH user cloud-init creates (default {@value #DEFAULT_SSH_USER}). */
    public VmContainer withSshUser(String user) {
        this.sshUser = CloudInitSeed.validateUser(user);
        return self();
    }

    /**
     * Bring-your-own image mode: no cloud-init seed is attached. The image must already contain
     * {@code user} with {@code privateKeyFile}'s public key authorized and passwordless sudo.
     */
    public VmContainer withoutCloudInit(String user, Path privateKeyFile) {
        this.cloudInitManaged = false;
        this.sshUser = CloudInitSeed.validateUser(user);
        this.privateKeyFile = Objects.requireNonNull(privateKeyFile, "privateKeyFile");
        return self();
    }

    /** Default timeout for {@link #execInVm(String...)} (default 5 minutes). */
    public VmContainer withExecTimeout(Duration timeout) {
        this.execTimeout = Objects.requireNonNull(timeout);
        return self();
    }

    /** How long to wait for the guest to power off on {@link #stop()} before the container is killed. */
    public VmContainer withShutdownTimeout(Duration timeout) {
        this.shutdownTimeout = Objects.requireNonNull(timeout);
        return self();
    }

    /**
     * Whether to bind-mount the image with the {@code z} SELinux option (relabels the file so the container
     * may read it on SELinux-enforcing hosts). Default true; disable if you manage labels yourself.
     */
    public VmContainer withSelinuxRelabel(boolean relabel) {
        this.relabelImageForSelinux = relabel;
        return self();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void configure() {
        if (!Files.isRegularFile(image)) {
            throw new IllegalArgumentException("guest image not found: " + image);
        }
        if (!softwareEmulation) {
            KvmSupport.kvmProblem().ifPresent(problem -> {
                throw new IllegalStateException("Cannot start VM container: " + problem);
            });
        }
        KvmSupport.remoteDockerProblem().ifPresent(problem -> {
            throw new IllegalStateException("Cannot start VM container: " + problem);
        });

        // Ports given through plain withExposedPorts() are treated as VM ports too.
        vmPorts.addAll(getExposedPorts());
        VmConfig config = new VmConfig(image, cpus, memoryMb, diskGb, vmPorts, softwareEmulation, subnet, extraQemuArgs);
        withEnv(config.toEnvironment());

        List<Integer> exposed = new ArrayList<>();
        exposed.add(SSH_PORT);
        config.vmPorts().stream().filter(p -> p != SSH_PORT).forEach(exposed::add);
        withExposedPorts(exposed.toArray(new Integer[0]));

        if (cloudInitManaged) {
            if (keyPair == null) {
                keyPair = SshKeyPair.generate();
            }
            String instanceId = CloudInitSeed.randomInstanceId();
            String host = hostname != null ? hostname : "vm-" + UUID.randomUUID().toString().substring(0, 8);
            CloudInitSeed seed = new CloudInitSeed(instanceId, host, sshUser, keyPair.openSshPublicKey(), userData);
            withCopyToContainer(Transferable.of(seed.userData().getBytes(StandardCharsets.UTF_8), 0644),
                    VmConfig.SEED_DIR + "/user-data");
            withCopyToContainer(Transferable.of(seed.metaData().getBytes(StandardCharsets.UTF_8), 0644),
                    VmConfig.SEED_DIR + "/meta-data");
        } else if (userData != null) {
            throw new IllegalStateException("withUserData() has no effect together with withoutCloudInit()");
        }

        if (hostConfigModifierInstalled) {
            return; // configure() runs on every start(); the modifier below must be registered once
        }
        hostConfigModifierInstalled = true;
        withCreateContainerCmdModifier(cmd -> {
            HostConfig hc = cmd.getHostConfig();
            if (hc == null) {
                hc = HostConfig.newHostConfig();
                cmd.withHostConfig(hc);
            }
            List<Bind> binds = new ArrayList<>();
            if (hc.getBinds() != null) {
                binds.addAll(Arrays.asList(hc.getBinds()));
            }
            binds.add(new Bind(image.toString(), new Volume(VmConfig.IMAGE_MOUNT_PATH), AccessMode.ro,
                    relabelImageForSelinux ? SELContext.shared : SELContext.none));
            hc.withBinds(binds);

            List<Device> devices = new ArrayList<>();
            if (hc.getDevices() != null) {
                devices.addAll(Arrays.asList(hc.getDevices()));
            }
            devices.add(Device.parse("/dev/net/tun:/dev/net/tun:rwm"));
            if (!softwareEmulation) {
                devices.add(Device.parse("/dev/kvm:/dev/kvm:rwm"));
            }
            hc.withDevices(devices);

            List<Capability> caps = new ArrayList<>();
            if (hc.getCapAdd() != null) {
                caps.addAll(Arrays.asList(hc.getCapAdd()));
            }
            if (!caps.contains(Capability.NET_ADMIN)) {
                caps.add(Capability.NET_ADMIN);
            }
            hc.withCapAdd(caps.toArray(new Capability[0]));

            // /proc/sys is not writable from inside the container (AppArmor), so the routing sysctls
            // the launcher needs are set here at creation time. Docker permits namespaced net.* keys.
            Map<String, String> sysctls = new LinkedHashMap<>();
            if (hc.getSysctls() != null) {
                sysctls.putAll(hc.getSysctls());
            }
            sysctls.putIfAbsent("net.ipv4.ip_forward", "1");
            sysctls.putIfAbsent("net.ipv4.conf.all.route_localnet", "1");
            hc.withSysctls(sysctls);
        });
    }

    @Override
    protected void containerIsStopping(InspectContainerResponse containerInfo) {
        closeSsh();
        try {
            LOG.info("Asking VM {} to power off (up to {})", getContainerName(), shutdownTimeout);
            ExecResult r = execInContainer("vm-shutdown", Long.toString(Math.max(1, shutdownTimeout.toSeconds())));
            if (!r.getStderr().isBlank()) {
                LOG.debug("vm-shutdown: {}", r.getStderr().strip());
            }
        } catch (Exception e) {
            LOG.warn("Graceful VM shutdown failed; the container will be killed: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ guest access

    /** Host-side port mapped to the guest's sshd. */
    public int getSshPort() {
        return getMappedPort(SSH_PORT);
    }

    public String getSshUser() {
        return sshUser;
    }

    /** The generated key pair, or {@code null} in bring-your-own mode. */
    public SshKeyPair getSshKeyPair() {
        return keyPair;
    }

    public boolean isCloudInitManaged() {
        return cloudInitManaged;
    }

    /** Serial console output captured so far (same as {@link #getLogs()}). */
    public String getSerialConsole() {
        return getLogs();
    }

    /** Runs a command as root (via {@code sudo -n}). */
    public VmExecResult execInVm(String... command) {
        return execInVm(execTimeout, command);
    }

    public VmExecResult execInVm(Duration timeout, String... command) {
        List<String> full = new ArrayList<>(List.of("sudo", "-n", "-H", "--"));
        full.addAll(Arrays.asList(command));
        return runCommandLine(VmSsh.shellJoin(full), timeout);
    }

    /** Runs a command as the (unprivileged) SSH user. */
    public VmExecResult execInVmAsUser(String... command) {
        return execInVmAsUser(execTimeout, command);
    }

    public VmExecResult execInVmAsUser(Duration timeout, String... command) {
        return runCommandLine(VmSsh.shellJoin(Arrays.asList(command)), timeout);
    }

    /** Runs a raw shell command line as root through {@code bash -c}. */
    public VmExecResult execShellInVm(String script) {
        return execInVm("bash", "-c", script);
    }

    private VmExecResult runCommandLine(String commandLine, Duration timeout) {
        try {
            return ssh().exec(commandLine, timeout);
        } catch (IOException e) {
            throw new UncheckedIOException("ssh exec failed: " + commandLine, e);
        }
    }

    /** Copies a local file into the guest at {@code guestPath}, owned by root with the given mode. */
    public void copyFileToVm(Path localFile, String guestPath, String mode) {
        String tmp = "/tmp/vmtc-" + UUID.randomUUID();
        try {
            ssh().put(localFile, tmp);
        } catch (IOException e) {
            throw new UncheckedIOException("sftp upload failed: " + localFile, e);
        }
        String parent = guestPath.contains("/") ? guestPath.substring(0, guestPath.lastIndexOf('/')) : ".";
        execInVm("bash", "-c", "mkdir -p " + VmSsh.shellQuote(parent.isEmpty() ? "/" : parent)
                + " && install -o root -g root -m " + VmSsh.shellQuote(mode) + " "
                + VmSsh.shellQuote(tmp) + " " + VmSsh.shellQuote(guestPath)
                + " && rm -f " + VmSsh.shellQuote(tmp)).expectSuccess();
    }

    public void copyFileToVm(Path localFile, String guestPath) {
        copyFileToVm(localFile, guestPath, "0644");
    }

    /** Copies a file out of the guest (read as root) to {@code localFile}. */
    public void copyFileFromVm(String guestPath, Path localFile) {
        String tmp = "/tmp/vmtc-" + UUID.randomUUID();
        execInVm("bash", "-c", "cp " + VmSsh.shellQuote(guestPath) + " " + VmSsh.shellQuote(tmp)
                + " && chown " + VmSsh.shellQuote(sshUser) + " " + VmSsh.shellQuote(tmp)).expectSuccess();
        try {
            ssh().get(tmp, localFile);
        } catch (IOException e) {
            throw new UncheckedIOException("sftp download failed: " + guestPath, e);
        } finally {
            execInVmAsUser("rm", "-f", tmp);
        }
    }

    /** The connected sshj client, for streaming or long-running commands the helpers do not cover. */
    public SSHClient sshClient() {
        try {
            return ssh().ensureConnected();
        } catch (IOException e) {
            throw new UncheckedIOException("ssh connect failed", e);
        }
    }

    VmSsh ssh() {
        VmSsh s = ssh;
        if (s == null) {
            synchronized (this) {
                s = ssh;
                if (s == null) {
                    if (!isRunning()) {
                        throw new IllegalStateException("VM container is not running");
                    }
                    s = new VmSsh(getHost(), getSshPort(), sshUser, keyProvider(), sshConnectTimeout);
                    ssh = s;
                }
            }
        }
        return s;
    }

    private KeyProvider keyProvider() {
        if (cloudInitManaged) {
            return VmSsh.keyProvider(keyPair);
        }
        try {
            return VmSsh.keyProvider(privateKeyFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load private key " + privateKeyFile, e);
        }
    }

    private void closeSsh() {
        VmSsh s = ssh;
        if (s != null) {
            s.close();
            ssh = null;
        }
    }

    // ------------------------------------------------------------------ runner image resolution

    /** Runner image: system property {@value #RUNNER_IMAGE_PROPERTY}, else the GHCR image tagged with this library's version. */
    public static DockerImageName defaultRunnerImage() {
        String override = System.getProperty(RUNNER_IMAGE_PROPERTY);
        if (override == null || override.isBlank()) {
            override = System.getenv("VM_TESTCONTAINERS_RUNNER_IMAGE");
        }
        if (override != null && !override.isBlank()) {
            return DockerImageName.parse(override.trim());
        }
        return DockerImageName.parse(DEFAULT_RUNNER_IMAGE + ":" + libraryVersion());
    }

    static String libraryVersion() {
        try (InputStream in = VmContainer.class.getResourceAsStream("/vm-testcontainers.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank() && !v.contains("${")) {
                    return v.trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "latest";
    }
}
