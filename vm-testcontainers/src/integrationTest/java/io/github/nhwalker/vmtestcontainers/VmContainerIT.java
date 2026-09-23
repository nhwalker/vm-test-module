package io.github.nhwalker.vmtestcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.github.nhwalker.vmtestcontainers.junit.EnabledIfKvmAvailable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots real VMs. Requires a local Docker daemon, /dev/kvm and {@code -Dvm.test.image=/path/to/rocky.qcow2}
 * (set by the Gradle task from {@code VM_TEST_IMAGE}).
 */
@Testcontainers
@EnabledIfKvmAvailable
class VmContainerIT {

    static final Path IMAGE = Path.of(System.getProperty("vm.test.image", "/nonexistent"));
    static final Network NETWORK = Network.newNetwork();

    static final String USER_DATA = """
            #cloud-config
            write_files:
              - path: /etc/vmtc-marker
                content: "cloud-init ran\\n"
                permissions: "0644"
              - path: /srv/www/hello.txt
                content: "hello from vm\\n"
                permissions: "0644"
            runcmd:
              - systemctl is-active firewalld && firewall-cmd --add-port=8080/tcp || true
              - systemd-run --unit=vmtc-http --working-directory=/srv/www python3 -m http.server 8080
            """;

    @Container
    static final VmContainer VM = new VmContainer(IMAGE)
            .withVmExposedPorts(8080)
            .withUserData(USER_DATA)
            .withHostname("vmweb")
            .withNetwork(NETWORK)
            .withNetworkAliases("vmweb");

    @Container
    static final GenericContainer<?> DEP = new GenericContainer<>("nginx:alpine")
            .withNetwork(NETWORK)
            .withNetworkAliases("dep")
            .withExposedPorts(80);

    @BeforeAll
    static void requireImage() {
        assumeTrue(Files.isRegularFile(IMAGE), "set VM_TEST_IMAGE to a Rocky 9 GenericCloud qcow2");
    }

    @Test
    void bootsAndRunsCommandsAsRootAndAsUser() {
        assertThat(VM.execInVm("id", "-u").expectSuccess().stdout().strip()).isEqualTo("0");
        assertThat(VM.execInVmAsUser("id", "-un").expectSuccess().stdout().strip()).isEqualTo("tc");
        assertThat(VM.execInVm("hostname").expectSuccess().stdout().strip()).isEqualTo("vmweb");
        assertThat(VM.execInVm("getenforce").expectSuccess().stdout().strip()).isEqualTo("Enforcing");
        assertThat(VM.execInVm("cat", "/etc/os-release").stdout()).contains("ID=\"rocky\"").contains("VERSION_ID=\"9");
    }

    @Test
    void nonZeroExitCodesAndStderrAreReported() {
        VmExecResult r = VM.execInVm("bash", "-c", "echo oops >&2; exit 3");
        assertThat(r.exitCode()).isEqualTo(3);
        assertThat(r.stderr()).contains("oops");
        assertThat(r.succeeded()).isFalse();
    }

    @Test
    void callerUserDataRanAndSshUserWasStillCreated() {
        assertThat(VM.execInVm("cat", "/etc/vmtc-marker").expectSuccess().stdout()).isEqualTo("cloud-init ran\n");
        assertThat(VM.execInVm("cloud-init", "status").stdout()).contains("done");
    }

    @Test
    void declaredPortIsReachableFromTheHost() throws Exception {
        String body = httpGet("http://" + VM.getHost() + ":" + VM.getMappedPort(8080) + "/hello.txt");
        assertThat(body).isEqualTo("hello from vm\n");
    }

    @Test
    void vmReachesOtherContainersByNetworkAlias() {
        VmExecResult r = VM.execInVm("curl", "-sSf", "-m", "10", "http://dep/");
        assertThat(r.expectSuccess().stdout()).contains("nginx");
    }

    @Test
    void otherContainersReachTheVmByItsAlias() throws Exception {
        try (GenericContainer<?> client = new GenericContainer<>("curlimages/curl:latest")
                .withNetwork(NETWORK)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sleep").withCmd("infinity"))) {
            client.start();
            GenericContainer.ExecResult r = client.execInContainer("curl", "-sSf", "-m", "10", "http://vmweb:8080/hello.txt");
            assertThat(r.getExitCode()).as(r.getStderr()).isEqualTo(0);
            assertThat(r.getStdout()).isEqualTo("hello from vm\n");
        }
    }

    @Test
    void copyFilesInAndOut(@TempDir Path tmp) throws IOException {
        Path in = tmp.resolve("payload.bin");
        byte[] payload = new byte[64 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        Files.write(in, payload);

        VM.copyFileToVm(in, "/root/incoming/payload.bin", "0600");
        VmExecResult stat = VM.execInVm("stat", "-c", "%U:%G %a", "/root/incoming/payload.bin").expectSuccess();
        assertThat(stat.stdout().strip()).isEqualTo("root:root 600");

        Path out = tmp.resolve("payload.out");
        VM.copyFileFromVm("/root/incoming/payload.bin", out);
        assertThat(Files.readAllBytes(out)).isEqualTo(payload);
    }

    @Test
    void rawSshClientIsAvailableForStreaming() throws Exception {
        var session = VM.sshClient().startSession();
        try (session) {
            var cmd = session.exec("echo streamed");
            String out = new String(cmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            cmd.join(30, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(out).isEqualTo("streamed\n");
            assertThat(cmd.getExitStatus()).isEqualTo(0);
        }
    }

    @Test
    void twoVmsOnOneNetworkReachEachOther() {
        try (VmContainer second = new VmContainer(IMAGE)
                .withHostname("vm2")
                .withNetwork(NETWORK)
                .withNetworkAliases("vm2")) {
            second.start();
            // second -> first via declared port
            assertThat(second.execInVm("curl", "-sSf", "-m", "10", "http://vmweb:8080/hello.txt").expectSuccess().stdout())
                    .isEqualTo("hello from vm\n");
            // first -> second via the always-forwarded ssh port
            VmExecResult banner = VM.execInVm("bash", "-c", "exec 3<>/dev/tcp/vm2/22 && head -c 4 <&3");
            assertThat(banner.expectSuccess().stdout()).isEqualTo("SSH-");
        }
    }

    @Test
    void stopPowersTheGuestOffGracefully() {
        ToStringConsumer console = new ToStringConsumer();
        VmContainer vm = new VmContainer(IMAGE)
                .withNetwork(NETWORK)
                .withLogConsumer(console);
        vm.start();
        vm.execInVm("true").expectSuccess();
        vm.stop();
        assertThat(console.toUtf8String()).containsPattern("(?i)reboot: Power down|Power down");
    }

    /** GET with a few retries: runcmd starts the guest's web server asynchronously. */
    private static String httpGet(String url) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        Exception last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                last = new IllegalStateException("HTTP " + resp.statusCode() + " from " + url);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(2000);
        }
        throw last;
    }
}
