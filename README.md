# vm-testcontainers

A [Testcontainers for Java](https://java.testcontainers.org/) module that boots a real Linux virtual
machine (Rocky 9, RHEL 9 or any other cloud image) under QEMU/KVM inside a container and hands your
JUnit 5 tests an SSH connection to it.

Use it when a container is not enough: systemd services, kernel modules, SELinux enforcing, firewalld,
sysctls, whole-host installers, config-management scripts, or several hosts talking to each other.

```java
@Testcontainers
@EnabledIfKvmAvailable
class NginxOnRockyTest {

    @Container
    static final VmContainer VM = new VmContainer(Path.of("/opt/images/Rocky-9-GenericCloud-Base.latest.x86_64.qcow2"))
            .withVmExposedPorts(80)
            .withUserData("""
                    #cloud-config
                    packages: [nginx]
                    runcmd:
                      - systemctl enable --now nginx
                      - firewall-cmd --add-service=http
                    """);

    @Test
    void servesHttp() throws Exception {
        VM.execInVm("systemctl", "is-active", "nginx").expectSuccess();
        var url = "http://" + VM.getHost() + ":" + VM.getMappedPort(80) + "/";
        // ... plain HTTP client call ...
    }
}
```

## Requirements

| | |
|---|---|
| Host OS | Linux. The Docker daemon must be **local** (the guest image is bind-mounted, not copied). |
| Virtualization | `/dev/kvm` readable and writable by your user (`sudo usermod -aG kvm $USER`). Without it, `withSoftwareEmulation(true)` runs QEMU's TCG interpreter: boots take minutes. |
| Container privileges | `--device /dev/kvm`, `--device /dev/net/tun`, `--cap-add NET_ADMIN`. Never `--privileged`. |
| Java | 21+ for the library (Testcontainers 2.x, sshj 0.41). |
| Guest image | A qcow2 cloud image with cloud-init: Rocky 9 GenericCloud ([download](https://dl.rockylinux.org/pub/rocky/9/images/x86_64/)) or the RHEL 9 KVM guest image from Red Hat. x86_64 only. |

GitHub-hosted `ubuntu-latest` x86_64 runners expose `/dev/kvm`; see `.github/workflows/ci.yml` for the
udev rule that makes it writable.

## Dependency

```groovy
repositories {
    maven { url = uri('https://maven.pkg.github.com/nhwalker/vm-test-module') } // GitHub Packages needs a token
}
dependencies {
    testImplementation 'io.github.nhwalker:vm-testcontainers:<version>'
    testImplementation 'org.testcontainers:testcontainers-junit-jupiter:2.0.5'
}
```

The library pins the runner image `ghcr.io/nhwalker/vm-testcontainers-qemu:<same version>`. Override with
`-Dvm.testcontainers.runner-image=...` or the `VM_TESTCONTAINERS_RUNNER_IMAGE` environment variable.

## How it works

```
 test JVM ──ssh──▶ host:mapped port ──Docker -p──▶ runner container ──iptables DNAT──▶ guest 10.200.0.2
                                                       │ eth0 (Docker network)   tap0 │
 other containers ──http://vmalias:8080──▶ ────────────┘     dnsmasq (DHCP+DNS) ◀──────┘ guest ──▶ http://dep/
```

* The runner container (`runner-image/`) creates a copy-on-write qcow2 overlay of your image, builds a
  cloud-init NoCloud ISO, brings up a `tap0` NIC on a private /24, and `exec`s `qemu-system-x86_64`.
  The base image is never written to.
* cloud-init creates user `tc` with passwordless sudo and a fresh Ed25519 key generated per container.
* Port 22 and every port from `withVmExposedPorts()` are DNATed from the container's `eth0` to the
  guest, so the guest is reachable **from the host** through `getMappedPort()` and **from other
  containers** through this container's network alias, for those ports only (TCP).
* The guest's outbound traffic is NATed through the container, and dnsmasq forwards DNS to Docker's
  embedded resolver, so the guest resolves other containers' aliases.
* The serial console is the container's stdout: `withLogConsumer()` and startup-failure messages show
  the kernel boot.
* `stop()` sends an ACPI power-off over QMP and waits up to 30 s (`withShutdownTimeout`) before the
  container is killed.

## API

| Method | |
|---|---|
| `new VmContainer(Path qcow2)` | Boot this image with the pinned runner image. |
| `withCpus(n)`, `withMemory(mb)`, `withDiskSize(gb)` | Defaults 2 vCPU, 2048 MiB, image's own disk size. Growing the disk relies on cloud-init `growpart`. |
| `withVmExposedPorts(ports...)` | Guest TCP ports to forward. `getMappedPort(port)` works for them. |
| `withUserData(String)` | Extra cloud-init user-data: `#cloud-config`, `#!` script, or `#cloud-boothook`. Merged with the module's part; lists (`users`, `packages`, `runcmd`) are appended. |
| `withHostname(String)`, `withSshUser(String)` | Guest hostname; name of the SSH/sudo user (default `tc`). |
| `withoutCloudInit(user, privateKeyFile)` | Bring-your-own image with a user and key already baked in. No seed ISO is attached. |
| `withSoftwareEmulation(true)` | Run without KVM (slow). |
| `execInVm(cmd...)` | Run as root via `sudo -n`. Returns `VmExecResult(exitCode, stdout, stderr)`. |
| `execInVmAsUser(cmd...)`, `execShellInVm(script)` | Run as the SSH user; run a `bash -c` script as root. |
| `copyFileToVm(local, guestPath[, mode])`, `copyFileFromVm(guestPath, local)` | SFTP transfer, root-owned on the guest. |
| `sshClient()` | The connected sshj `SSHClient`, for streaming output or long-running commands. |
| `waitingFor(...)` | Replace the default readiness check (SSH auth, then `cloud-init status --wait`). |

`@EnabledIfKvmAvailable` (package `...vmtestcontainers.junit`) skips a test class when `/dev/kvm` is
not usable, unless `-Dvm.testcontainers.software-emulation=true`.

### Dropping in RHEL 9

Point `VmContainer` at the RHEL 9 KVM guest image instead of Rocky. The module does not register the
system with Red Hat; if a test needs `dnf`, do the registration in `withUserData()` or use a pre-registered
image. Nothing else changes.

## Building this repository

```
./gradlew build                     # compiles and runs unit tests (no Docker needed)
runner-image/smoke-test.sh rocky.qcow2 [tag]   # boots the runner image with plain docker
VM_TEST_IMAGE=/path/to/rocky.qcow2 ./gradlew integrationTest   # needs Docker + /dev/kvm
```

To test against a locally built runner image:

```
docker build -t vm-testcontainers-qemu:dev runner-image
VM_TEST_IMAGE=... VM_TEST_RUNNER_IMAGE=vm-testcontainers-qemu:dev ./gradlew integrationTest
```

Releases: push a `v1.2.3` tag. `release.yml` pushes the runner image to GHCR and the jar to GitHub Packages
with version `1.2.3`.

## Troubleshooting

* **"/dev/kvm does not exist"**: enable VT-x/AMD-V in firmware; on a VM host, enable nested virtualization.
* **"/dev/kvm exists but is not readable/writable"**: `sudo usermod -aG kvm $USER` and log in again.
* **"DOCKER_HOST ... points at a remote daemon"**: the qcow2 is bind-mounted from this machine; use a local daemon.
* **Timed out waiting for SSH**: read the serial console in the exception message. Common causes: the image is
  not a cloud image (no cloud-init, so no user/key); SELinux on the host blocking the bind mount (the module
  mounts with the `z` option by default, see `withSelinuxRelabel`); not enough RAM.
* **cloud-init reported failure**: run `vm.execInVm("cat", "/var/log/cloud-init-output.log")` from a test
  that uses `waitingFor(new VmReadyWaitStrategy().withoutCloudInitCheck())`.
* **Guest cannot reach the internet**: the guest NATs through the container. If the container has no
  outbound access (corporate proxy), neither does the guest.
* **UDP services**: only TCP ports are forwarded in this version.

## Limitations (v1)

One VM per container, one NIC, one root disk, x86_64 guests only, TCP port forwarding only.
