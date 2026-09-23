# TestContainerVM: specification and build plan

A Testcontainers for Java module that boots a real Linux virtual machine (Rocky 9, RHEL 9, or any
cloud-init capable qcow2) under QEMU/KVM inside a Docker container, and hands JUnit 5 tests an SSH
connection to it.

This document is written so that an engineer or an automated agent can rebuild the project from
scratch without access to the prototype. Everything marked **verified** was observed working on a
GitHub-hosted `ubuntu-latest` runner with the Rocky 9.8 GenericCloud image. Everything marked
**unverified** is a design expectation that has not been exercised. The prototype lives in this
repository and can be consulted, but the spec is intended to stand alone.

---

## 1. Goals and non-goals

### Goals

- Tests that need a real kernel: systemd services, kernel modules, SELinux enforcing, firewalld,
  sysctls, whole-host installers, config-management scripts, and multi-host scenarios.
- Ordinary Testcontainers ergonomics: `@Container` static fields, `start()`/`stop()`, `Network`,
  `getMappedPort()`, log consumers, Ryuk cleanup.
- Guest image supplied at runtime by the caller. Rocky 9 GenericCloud for development and CI;
  the RHEL 9 KVM guest image must drop in with no code change.
- Boot to ready in well under a minute with KVM (**verified**: 18 to 19 seconds on a 4 vCPU
  GitHub runner).

### Non-goals (v1)

- macOS, Windows, or remote Docker daemons as the host.
- Red Hat subscription registration. If a test needs `dnf` on RHEL, the caller does it in user-data.
- More than one NIC, more than one disk, aarch64 guests, UDP port forwarding.
- Multiple VMs inside one container. Multi-host tests use several containers on one `Network`.

---

## 2. Decisions (fixed unless the spec is revised)

| Area | Decision |
|---|---|
| Language / build | Java 21, Gradle with the Groovy DSL, Gradle wrapper committed |
| Coordinates | `io.github.nhwalker:vm-testcontainers`, package `io.github.nhwalker.vmtestcontainers` |
| Testcontainers | 2.0.x (`org.testcontainers:testcontainers`, JUnit 5 via `org.testcontainers:testcontainers-junit-jupiter`). JUnit 4 is gone in 2.x |
| SSH library | sshj 0.41.x (`com.hierynomus:sshj`). Java 17+. Pulls Bouncy Castle as a runtime dependency |
| Hypervisor | QEMU with KVM. TCG software emulation is an explicit opt-in, never a silent fallback |
| Runner image | Built and published by this repo (Debian bookworm-slim base), tagged with the library version |
| Guest access | SSH, key generated per container (Ed25519 via the JDK), injected with a cloud-init NoCloud seed |
| Readiness | SSH key auth succeeds AND `cloud-init status --wait` returns; replaceable via `waitingFor()` |
| Exec API | `execInVm(...)` runs as root through `sudo -n`; `execInVmAsUser(...)` as the SSH user; raw sshj client exposed |
| Networking | tap NIC in the container, private /24, dnsmasq for DHCP and DNS, iptables MASQUERADE out and DNAT in for declared ports only |
| Privileges | `--device /dev/kvm`, `--device /dev/net/tun`, `--cap-add NET_ADMIN`, `--sysctl net.ipv4.ip_forward=1`. Never `--privileged` |
| Shutdown | ACPI power-off over QMP with a timeout, then Testcontainers kills the container |
| Sizing defaults | 2 vCPU, 2048 MiB, disk as shipped in the image; all overridable |
| Publishing | Runner image to GHCR, jar to GitHub Packages, on `v*` tags |

---

## 3. Architecture

```
 test JVM ──ssh──▶ host:mapped port ──Docker -p──▶ runner container ──iptables DNAT──▶ guest 10.200.0.2
                                                       │ eth0 (Docker network)   tap0 │
 other containers ──http://vmalias:8080──▶ ────────────┘     dnsmasq (DHCP+DNS) ◀──────┘ guest ──▶ http://dep/
```

Two deliverables:

1. **Runner image** (`runner-image/`): a Docker image containing QEMU and a launcher script. It takes a
   read-only qcow2 bind mount and environment variables, creates a copy-on-write overlay, builds the
   cloud-init seed ISO, sets up networking, and runs QEMU in the foreground with the serial console on
   stdout.
2. **Java library** (`vm-testcontainers/`): a `GenericContainer` subclass that configures the runner
   container, waits for the guest, and wraps sshj for exec and file transfer.

The two are version-coupled: the library pins `ghcr.io/<owner>/vm-testcontainers-qemu:<library version>`
and reads its own version from a resource file filled in by Gradle at build time.

---

## 4. Runner image

### 4.1 Contents

Base `debian:bookworm-slim`. Packages (**verified** sufficient): `qemu-system-x86 qemu-utils seabios
ipxe-qemu iproute2 iptables dnsmasq genisoimage socat procps ca-certificates`. Image size is about
215 MB. Three scripts installed in `/usr/local/bin`, all `0755`:

- `vm-run.sh`: entrypoint.
- `vm-net-up.sh`: networking; prints the guest IP on stdout.
- `vm-shutdown`: ACPI power-off via QMP.

`WORKDIR /vm`, `mkdir -p /vm/seed /images`, `EXPOSE 22`, `ENTRYPOINT ["/usr/local/bin/vm-run.sh"]`.

### 4.2 Environment contract (consumed by `vm-run.sh`)

| Variable | Default | Meaning |
|---|---|---|
| `VM_IMAGE` | `/images/base.qcow2` | read-only base image |
| `VM_CPUS` | `2` | `-smp` |
| `VM_MEM_MB` | `2048` | `-m` |
| `VM_DISK_GB` | unset | if set, `qemu-img resize` the overlay to N GiB; cloud-init `growpart` expands `/` |
| `VM_ACCEL` | `kvm` | `kvm` or `tcg` |
| `VM_PORTS` | empty | comma-separated guest TCP ports to DNAT from `eth0`; 22 is always included |
| `VM_SUBNET` | `10.200.0.0/24` | tap subnet; container gets `.1`, guest gets `.2` |
| `VM_SEED_DIR` | `/vm/seed` | if it contains `user-data`, build a `cidata` ISO from it |
| `VM_QEMU_EXTRA_ARGS` | empty | appended verbatim to the qemu command line |

### 4.3 `vm-run.sh` sequence

1. **Preflight.** Each failure prints one line starting with `[vm-run] ERROR:` naming the missing
   device or capability and exits 1, so the Java side can surface it from the container log.
   - `VM_IMAGE` must be readable.
   - For `kvm`: `/dev/kvm` must exist and be readable and writable. Suggest `--device /dev/kvm` or `VM_ACCEL=tcg`.
   - `/dev/net/tun` must exist.
   - Integer validation of `VM_CPUS` (>= 1), `VM_MEM_MB` (>= 256), `VM_DISK_GB`.
2. **Overlay.** `rm -f /vm/overlay.qcow2; qemu-img create -q -f qcow2 -b "$VM_IMAGE" -F qcow2 /vm/overlay.qcow2`,
   then optional `qemu-img resize`. The base image is never written. The overlay lives in the container's
   writable layer (**verified** adequate for tests).
3. **Seed ISO.** If `$VM_SEED_DIR/user-data` is non-empty: write a default `meta-data` if missing, then
   `genisoimage -quiet -output /vm/seed.iso -volid cidata -joliet -rock user-data meta-data [network-config]`.
   The volume label `cidata` is what cloud-init's NoCloud datasource looks for.
4. **Networking.** `GUEST_IP=$(vm-net-up.sh "$VM_SUBNET" "$GUEST_MAC" "$VM_PORTS")` with a fixed MAC
   such as `52:54:00:c0:ff:ee`.
5. **QEMU** via `exec` so it becomes PID 1 and its exit ends the container:

```
qemu-system-x86_64 \
  -machine q35,accel=kvm -cpu host            # tcg: -machine q35,accel=tcg -cpu max
  -smp "$VM_CPUS" -m "${VM_MEM_MB}M" \
  -drive "file=/vm/overlay.qcow2,if=virtio,format=qcow2,cache=unsafe,discard=unmap" \
  -drive "file=/vm/seed.iso,media=cdrom,format=raw,read-only=on" \   # only when a seed exists
  -netdev "tap,id=net0,ifname=tap0,script=no,downscript=no" \
  -device "virtio-net-pci,netdev=net0,mac=$GUEST_MAC" \
  -device virtio-rng-pci \
  -display none -monitor none -serial stdio \
  -qmp "unix:/vm/qmp.sock,server,nowait" \
  $VM_QEMU_EXTRA_ARGS
```

Notes that cost time in the prototype:
- The seed ISO must not use `if=virtio` with `media=cdrom`; virtio-blk has no cdrom mode. Plain
  `-drive media=cdrom` attaches it to the q35 SATA controller.
- Do not pass `-no-reboot`; guests that reboot during a test (kernel module installs, `power_state`) would
  otherwise terminate QEMU.
- `-serial stdio` makes the kernel console the container's stdout, which is what `withLogConsumer()`
  and startup-failure messages show.

### 4.4 `vm-net-up.sh` (**verified**)

```
ip tuntap add dev tap0 mode tap        # fails without NET_ADMIN and /dev/net/tun -> exit 1 with message
ip addr add "$GW_IP/$PREFIX" dev tap0
ip link set tap0 up

ensure_sysctl net.ipv4.ip_forward 1 || exit 1            # see AppArmor note below
ensure_sysctl net.ipv4.conf.all.route_localnet 1 || warn # optional

iptables -t nat -A POSTROUTING -s "$SUBNET" ! -o tap0 -j MASQUERADE
iptables -t nat -A POSTROUTING -o tap0 -s 127.0.0.0/8 -j MASQUERADE   # docker exec -> 127.0.0.1 -> guest
iptables -A FORWARD -i tap0 -j ACCEPT
iptables -A FORWARD -o tap0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT
for p in 22 $VM_PORTS: 
  iptables -t nat -A PREROUTING ! -i tap0 -p tcp --dport $p -j DNAT --to-destination "$GUEST_IP:$p"
  iptables -t nat -A OUTPUT -o lo -p tcp --dport $p -j DNAT --to-destination "$GUEST_IP:$p"

dnsmasq --interface=tap0 --bind-interfaces --except-interface=lo \
  --dhcp-range="$GUEST_IP,$GUEST_IP,12h" --dhcp-host="$GUEST_MAC,$GUEST_IP" \
  --dhcp-option=option:router,"$GW_IP" --dhcp-option=option:dns-server,"$GW_IP" \
  --dhcp-authoritative --resolv-file=/etc/resolv.conf --no-hosts \
  --log-facility=/vm/dnsmasq.log --pid-file=/vm/dnsmasq.pid
echo "$GUEST_IP"
```

`ensure_sysctl` tries `sysctl -w`, ignores failure, then compares `sysctl -n` to the wanted value.
**Docker's default AppArmor profile denies writes under `/proc/sys` inside containers, even with
NET_ADMIN.** The values must be set at container creation with `--sysctl`, which Docker permits for
namespaced `net.*` keys. This was the first CI failure of the prototype.

Why DNS works for Docker aliases: on a user-defined network the container's `/etc/resolv.conf` points at
Docker's embedded resolver (127.0.0.11). dnsmasq forwards guest queries there from inside the container's
namespace, so `curl http://dep/` from the guest resolves another container's alias (**verified**).

Why other containers reach the guest by this container's alias: their packets arrive on `eth0`, hit the
PREROUTING DNAT, and are forwarded to the guest. Only declared ports are forwarded (**verified**).

### 4.5 `vm-shutdown [timeout-seconds]` (**verified**)

```
printf '{"execute":"qmp_capabilities"}\n{"execute":"system_powerdown"}\n' \
  | timeout 5 socat -t 2 - UNIX-CONNECT:/vm/qmp.sock >/dev/null 2>&1 || true
poll up to $timeout seconds for `pgrep -x qemu-system-x86` to disappear; always exit 0
```

QMP requires the `qmp_capabilities` handshake before any command. The process name to match is
`qemu-system-x86` (the kernel truncates `comm` to 15 characters).

**Important:** when the guest powers off, QEMU (PID 1) exits, the container exits, and Docker kills the
`docker exec` running `vm-shutdown` with exit code 137. That is the success path. Callers must not treat
the exec's exit code as the result; check the container's exit code (`docker wait`, expected 0) and the
serial console line `reboot: Power down` instead. This was the second CI failure of the prototype.

### 4.6 `smoke-test.sh /path/to/guest.qcow2 [image-tag]` (**verified**)

A Docker-only check that does not need Java: generates a throwaway ssh key, writes a minimal
`#cloud-config` seed creating user `tc` with passwordless sudo, runs the image with

```
docker run -d --device /dev/kvm --device /dev/net/tun --cap-add NET_ADMIN \
  --sysctl net.ipv4.ip_forward=1 --sysctl net.ipv4.conf.all.route_localnet=1 \
  -v "$IMAGE:/images/base.qcow2:ro" -v "$SEED:/vm/seed:ro" -p 127.0.0.1::22 "$TAG"
```

then polls `ssh` on the mapped port, runs `sudo cloud-init status --wait`, checks `getenforce`,
`/etc/os-release`, an outbound `curl`, calls `docker exec vm-shutdown 40 || true`, and verifies
`docker wait` returns 0 and the console contains `reboot: Power down`. On any failure it prints the
last lines of `docker logs`.

---

## 5. Java library

### 5.1 Classes

| Class | Responsibility |
|---|---|
| `VmContainer extends GenericContainer<VmContainer>` | Public API; configure, wait, exec, copy, graceful stop |
| `VmConfig` (record) | Immutable cpu/mem/disk/ports/accel/subnet; validation; `toEnvironment()` map for the runner |
| `CloudInitSeed` | Renders `meta-data` and `user-data` (plain or multipart MIME) |
| `SshKeyPair` | Generates an Ed25519 key with the JDK; renders the OpenSSH `authorized_keys` line |
| `VmSsh` | sshj wrapper: connect, exec with captured output, SFTP put/get, shell quoting |
| `VmReadyWaitStrategy extends AbstractWaitStrategy` | SSH probe loop, then `cloud-init status --wait` |
| `VmExecResult` (record) | `exitCode`, `stdout`, `stderr`, `succeeded()`, `expectSuccess()` |
| `KvmSupport` | Host preflight: `/dev/kvm`, `DOCKER_HOST` |
| `junit.EnabledIfKvmAvailable` + `KvmAvailableCondition` | JUnit 5 `ExecutionCondition` |

`VmExecResult` exists because Testcontainers' `Container.ExecResult` has a package-private constructor.

### 5.2 Public API

```java
VmContainer vm = new VmContainer(Path.of("/opt/images/Rocky-9-GenericCloud-Base.latest.x86_64.qcow2"))
    .withCpus(2).withMemory(2048).withDiskSize(20)
    .withVmExposedPorts(8080)
    .withUserData("#cloud-config\npackages: [nginx]\n")
    .withHostname("web")
    .withNetwork(net).withNetworkAliases("web");
vm.start();
VmExecResult r = vm.execInVm("systemctl", "is-active", "nginx");     // root via sudo -n -H --
vm.execInVmAsUser("id");                                              // as user "tc"
vm.execShellInVm("echo $HOSTNAME > /root/h");                         // bash -c as root
vm.copyFileToVm(Path.of("app.rpm"), "/root/app.rpm", "0644");         // sftp to /tmp, then sudo install
vm.copyFileFromVm("/var/log/messages", Path.of("build/messages"));    // sudo cp to /tmp, chown, sftp get
int port = vm.getMappedPort(8080);
SSHClient ssh = vm.sshClient();                                       // escape hatch
vm.getSshPort(); vm.getSshUser(); vm.getSshKeyPair(); vm.getSerialConsole();
```

Other configuration: `withSoftwareEmulation(boolean)`, `withGuestSubnet("10.9.9.0/24")`,
`withExtraQemuArgs(String)`, `withSshUser(String)`, `withoutCloudInit(user, privateKeyFile)` for
bring-your-own images, `withExecTimeout(Duration)` (default 5 min), `withShutdownTimeout(Duration)`
(default 30 s), `withSelinuxRelabel(boolean)` (default true). Constructor
`VmContainer(DockerImageName runner, Path image)` overrides the runner image; system property
`vm.testcontainers.runner-image` or env `VM_TESTCONTAINERS_RUNNER_IMAGE` override the default.

### 5.3 `configure()` (runs on every `start()`)

1. Validate the image path is a regular file.
2. Unless TCG: `KvmSupport.kvmProblem()` must be empty, else throw `IllegalStateException` with the fix.
   `KvmSupport.remoteDockerProblem()` rejects `DOCKER_HOST` values that are not `unix://` or `npipe://`,
   because the image is a bind mount.
3. Merge `getExposedPorts()` into the VM port set, build a `VmConfig`, `withEnv(config.toEnvironment())`,
   `withExposedPorts(22, declared...)`. Note `withExposedPorts` replaces rather than appends.
4. If cloud-init managed: generate the key pair (once), a random `instance-id`, hostname (given or
   `vm-<8 hex>`), render the seed, and `withCopyToContainer(Transferable.of(bytes, 0644), "/vm/seed/user-data")`
   plus `meta-data`. Testcontainers copies these after container creation and before start (**verified**).
5. Install a `withCreateContainerCmdModifier` once (guard with a boolean; `configure()` re-runs on
   restart) that edits `cmd.getHostConfig()`:
   - append `new Bind(imagePath, new Volume("/images/base.qcow2"), AccessMode.ro, SELContext.shared)`;
   - append devices `Device.parse("/dev/net/tun:/dev/net/tun:rwm")` and, unless TCG, `/dev/kvm`;
   - add `Capability.NET_ADMIN` to `capAdd`;
   - put `net.ipv4.ip_forward=1` and `net.ipv4.conf.all.route_localnet=1` into `sysctls`.
   Do not use `withFileSystemBind`; it is `@Deprecated` in Testcontainers 2.x and copy-to-container is
   unusable for multi-GB images.
6. Defaults set in the constructor: `waitingFor(new VmReadyWaitStrategy())`, `withStartupTimeout(5 min)`.

### 5.4 Readiness (`VmReadyWaitStrategy`) (**verified**)

- Phase 1: loop every 2 s until `execInVmAsUser(15 s, "true")` succeeds. If `isRunning()` is false, fail
  immediately with the last 60 lines of the container log. sshj logs an ERROR line ("Received end of
  connection, but no identification received") on each early attempt; it is harmless.
- Phase 2 (skipped when cloud-init is not managed): `execInVm(remaining, "cloud-init", "status", "--wait")`.
  Exit 0 is ready; exit 2 is "recoverable errors", log a warning and continue; anything else fails with
  output and the console tail.
- The whole thing is bounded by the startup timeout. `AbstractWaitStrategy` provides
  `waitStrategyTarget` and `startupTimeout`; cast the target to `VmContainer`.

### 5.5 SSH details (`VmSsh`)

- `SSHClient` with `PromiscuousVerifier` (guest host key is generated at first boot), connect timeout
  10 s, socket read timeout 0 (long commands are bounded by exec timeouts).
- Key provider: convert the JDK key pair through sshj's provider so signing uses key objects the
  configured provider understands:

```java
KeyFactory kf = net.schmizz.sshj.common.SecurityUtils.getKeyFactory("Ed25519");
KeyPair converted = new KeyPair(
    kf.generatePublic(new X509EncodedKeySpec(jdkPub.getEncoded())),
    kf.generatePrivate(new PKCS8EncodedKeySpec(jdkPriv.getEncoded())));
KeyProvider provider = new KeyPairWrapper(converted);
```

  sshj 0.41's `KeyType.ED25519` accepts any key whose algorithm is `Ed25519` or `EdDSA`, and encodes the
  public key as the last 32 bytes of `getEncoded()`. A unit test should sign with
  `new com.hierynomus.sshj.signature.SignatureEdDSA.Factory().create()` and verify with the JDK
  (**verified** passing).
- Exec: one `Session` per command; read stderr on a separate (virtual) thread so a chatty stderr cannot
  stall the channel window while stdout is being drained; `cmd.join(timeout)`; `getExitStatus()` may be
  null, map to -1.
- Shell quoting: arguments matching `[A-Za-z0-9_./:=@%+,-]+` are passed as-is, everything else is
  single-quoted with `'\''` escaping. Root commands are `sudo -n -H -- <quoted args>`.
- SFTP: `client.newSFTPClient().put(new FileSystemFile(local), remote)` and `get(remote, new FileSystemFile(local))`.
  Uploads go to `/tmp/vmtc-<uuid>` as the SSH user, then `install -o root -g root -m <mode>` into place.
  Downloads are `cp` + `chown <user>` into `/tmp` as root, then fetched and removed.

### 5.6 cloud-init seed (`CloudInitSeed`) (**verified**)

`meta-data`:
```
instance-id: vmtc-<uuid>
local-hostname: <hostname>
```

Module cloud-config:
```yaml
#cloud-config
merge_how: "list(append)+dict(no_replace,recurse_list)+str()"
ssh_pwauth: false
users:
  - default
  - name: tc
    gecos: vm-testcontainers ssh user
    shell: /bin/bash
    lock_passwd: true
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    ssh_authorized_keys:
      - "ssh-ed25519 AAAA... vm-testcontainers"
```

When the caller supplies user-data, produce a `multipart/mixed` MIME message with the caller's part
first and the module's part last. The `merge_how` key in the later part controls how it merges into the
earlier ones, so caller `users`, `packages`, `runcmd` lists are appended to rather than replaced. Caller
content type is detected from the first line: `#cloud-config` -> `text/cloud-config`, `#!` ->
`text/x-shellscript`, `#cloud-boothook` -> `text/cloud-boothook`; anything else is rejected. Each part
carries `Content-Type: <type>; charset="utf-8"`, `MIME-Version: 1.0`, `Content-Transfer-Encoding: 8bit`,
`Content-Disposition: attachment; filename="..."`. A caller cloud-config with `write_files` and `runcmd`
merged correctly with the module's `users` in CI.

### 5.7 Graceful stop

Override `containerIsStopping(InspectContainerResponse)`: close the SSH client, then
`execInContainer("vm-shutdown", "<seconds>")` inside a try/catch that only logs. Testcontainers'
`GenericContainer.stop()` then calls `ResourceReaper.stopAndRemoveContainer`, which inspects the
container and only issues `kill` if it is still running, so a guest that already powered off is removed
cleanly (**verified**: `reboot: Power down` observed through a `ToStringConsumer`).

### 5.8 Runner image version

`src/main/resources/vm-testcontainers.properties` contains `version=${version}`, expanded by Gradle's
`processResources` with `expand(version: project.version)`. `VmContainer.libraryVersion()` reads it and
falls back to `latest` if unexpanded.

---

## 6. Gradle

- Root `settings.gradle` includes `vm-testcontainers`; `gradle.properties` holds `group` and `version`.
- `vm-testcontainers/build.gradle`: plugins `java-library`, `maven-publish`; toolchain 21;
  `withSourcesJar()`, `withJavadocJar()`; `javadoc` with `-Xdoclint:none`.
- Dependencies: `api org.testcontainers:testcontainers:2.0.5`, `implementation com.hierynomus:sshj:0.41.1`,
  `implementation org.slf4j:slf4j-api:2.0.17`, `compileOnly org.junit.jupiter:junit-jupiter-api` (for the
  condition), test deps JUnit 5, AssertJ, logback.
- A separate `integrationTest` source set and `Test` task: classpath includes main and test outputs;
  `integrationTestImplementation` extends `testImplementation` and adds
  `org.testcontainers:testcontainers-junit-jupiter`; the task maps env `VM_TEST_IMAGE` to system property
  `vm.test.image` and `VM_TEST_RUNNER_IMAGE` to `vm.testcontainers.runner-image`; `onlyIf { VM_TEST_IMAGE set }`;
  `outputs.upToDateWhen { false }`; `showStandardStreams = true`.
- `publishing` to `https://maven.pkg.github.com/<owner>/<repo>` with `GITHUB_ACTOR`/`GITHUB_TOKEN`.
- Gradle 9.x wrapper. On a rate-limited network, `-Dorg.gradle.internal.repository.max.tentatives=12
  -Dorg.gradle.internal.repository.initial.backoff=3000` gets past Maven Central 429s.

---

## 7. Tests

### 7.1 Unit tests (no Docker)

- `SshKeyPair`: OpenSSH line format (`ssh-ed25519`, 4+11+4+32 byte blob); raw encoding equals the last
  32 bytes of the JDK X.509 encoding; matches sshj's `Buffer.PlainBuffer().putPublicKey()`; sshj can sign
  and the JDK verifies. `EdECPoint` does not override `equals`, compare `getY()` and `isXOdd()`.
- `CloudInitSeed`: meta-data text; plain cloud-config when no caller data; multipart ordering (caller first,
  module last) and boundary count; shell script typed as `text/x-shellscript`; unknown first line rejected;
  user name validation.
- `VmConfig`: env mapping; ports sorted, de-duplicated, 22 excluded from `VM_PORTS`; validation errors.
  Do not build test inputs with `Set.of(...)` containing duplicates.
- `KvmSupport`: missing device, unreadable device (skipped as root), `DOCKER_HOST` detection.
- `VmSsh`: quoting rules. `VmContainer`: version resource is expanded; system property override.

### 7.2 Integration tests (Docker + KVM + image; all **verified** passing)

One static `VmContainer` with alias `vmweb`, `withVmExposedPorts(8080)`, hostname `vmweb`, and user-data
that writes `/etc/vmtc-marker` and `/srv/www/hello.txt`, opens 8080 in firewalld if active, and starts
`python3 -m http.server 8080` with `systemd-run`. One static `nginx:alpine` with alias `dep`. Tests:

1. `id -u` is 0 as root, `id -un` is `tc` as user, `hostname` is `vmweb`, `getenforce` is `Enforcing`,
   `/etc/os-release` says rocky 9.
2. Non-zero exit and stderr are reported.
3. Marker file exists and `cloud-init status` says done.
4. HTTP GET from the test JVM to `getMappedPort(8080)` returns the file (retry a few times; `runcmd`
   starts the server asynchronously).
5. `curl http://dep/` from the guest contains `nginx`.
6. A `curlimages/curl` container on the network (entrypoint overridden to `sleep infinity`) fetches
   `http://vmweb:8080/hello.txt`.
7. Copy a 64 KiB file in with mode 0600, `stat` shows `root:root 600`, copy it out, bytes equal.
8. Raw sshj session exec works.
9. A second VM on the network fetches from `vmweb:8080`; the first VM reads the `SSH-` banner from
   `vm2:22` via `/dev/tcp`.
10. `stop()` on a VM with a `ToStringConsumer` leaves `reboot: Power down` in the captured console.

A separate TCG test, tagged slow and gated on `-Dvm.test.tcg=true`, boots with `withSoftwareEmulation(true)`
and a 30 minute timeout (**unverified**).

---

## 8. CI (GitHub Actions) (**verified**)

`ci.yml` on push, pull_request, workflow_dispatch:

- **Build runner image**: `docker/build-push-action` with `outputs: type=docker,dest=/tmp/runner-image.tar`,
  GHA cache, upload as artifact.
- **Unit tests**: `actions/setup-java` 21 temurin, `gradle/actions/setup-gradle`, `./gradlew build`.
- **Integration tests (KVM)** on `ubuntu-latest`, needs the image job:
  1. Enable KVM for the runner user:
     ```
     echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
     sudo udevadm control --reload-rules && sudo udevadm trigger --name-match=kvm
     ```
  2. Download and `docker load` the image artifact.
  3. Fetch `Rocky-9-GenericCloud-Base.latest.x86_64.qcow2.CHECKSUM` from
     `https://dl.rockylinux.org/pub/rocky/9/images/x86_64/`, use `hashFiles` of it as the `actions/cache`
     key for `images/rocky.qcow2`, download on miss, verify with
     `awk '/^SHA256/ {print $NF}'` against `sha256sum`. The image is about 616 MB and caches fine.
  4. Run `runner-image/smoke-test.sh images/rocky.qcow2 <tag>`.
  5. `VM_TEST_IMAGE=... VM_TEST_RUNNER_IMAGE=<tag> ./gradlew integrationTest`.
  6. Upload `vm-testcontainers/build/reports/tests` always.

`release.yml` on `v*` tags: derive the version from the tag, log in to GHCR with `GITHUB_TOKEN`, push the
image as `ghcr.io/<owner>/vm-testcontainers-qemu:<version>` and `:latest`, then
`./gradlew publish -Pversion=<version>`.

Observed timings: image build about 30 s, unit tests about 1 min, integration job about 3 to 5 min
including the image download.

---

## 9. Build order with verification gates

1. Gradle skeleton compiles an empty library. Gate: `./gradlew build`.
2. Runner image and scripts. Gate: `bash -n` on scripts; `docker build`; `smoke-test.sh` against a Rocky
   qcow2 on a KVM host (or in CI). Expect: ssh in about 12 s, `status: done`, `Enforcing`, `outbound ok`,
   `container exit code: 0`, `graceful power-off observed`.
3. `SshKeyPair`, `CloudInitSeed`, `VmConfig`, `KvmSupport`, `VmSsh` quoting with unit tests. Gate: the sshj
   signing test passes.
4. `VmSsh`, `VmReadyWaitStrategy`, `VmContainer`, JUnit condition. Gate: compiles; `integrationTest` source
   set compiles.
5. Integration tests. Gate: all pass in CI on the KVM runner.
6. Workflows, README, publishing. Gate: a tag produces the image and the jar.

---

## 10. Pitfalls encountered (do not rediscover these)

- Testcontainers 2.x: JUnit 4 removed; artifacts renamed with the `testcontainers-` prefix; `withFileSystemBind`
  deprecated; `Container.ExecResult` constructor package-private; `stop()` uses `kill`, not a graceful stop;
  `containerIsStopping` is the hook for graceful work; `configure()` runs on every `start()`;
  `withExposedPorts` replaces the list.
- AppArmor blocks `/proc/sys` writes in containers; pass `--sysctl` at creation.
- `docker exec` exits 137 when PID 1 exits during the exec; for `vm-shutdown` that is success.
- The cidata ISO cannot be a virtio cdrom.
- `EdECPoint.equals` is not overridden.
- GitHub email privacy rejects pushes whose committer email is a protected address; use the noreply address.
- Maven Central returns intermittent 429s through some proxies; Gradle retry properties handle it.
- The CI workflow runs twice per push (push and pull_request triggers) unless a `concurrency` group or a
  branch filter is added.

---

## 11. Known gaps and proposed follow-ups

1. **Host KVM check is stricter than Docker needs.** `KvmSupport` requires `/dev/kvm` to be readable and
   writable by the test user. With a root-owned Docker daemon `--device /dev/kvm` works regardless, and the
   launcher performs the real check inside the container. Proposed: require existence only, warn on
   permissions, and keep the strict check as an option for rootless Docker or Podman.
2. **Network mode without NET_ADMIN (unverified).** Add `VM_NET=tap|user|auto`. `user` mode uses QEMU
   `-netdev user,hostfwd=tcp::22-:22,hostfwd=tcp::<p>-:<p>,...` (slirp): no tap, no iptables, no dnsmasq,
   no TUN device, no sysctls, so the only grant is `/dev/kvm`. Host to VM and container to VM on declared
   ports still work because QEMU listens on the container's interfaces; the guest sees all peers as the
   slirp gateway, throughput is lower, ICMP is unreliable, and real L2/L3 behavior is unavailable. `auto`
   tries the tap and falls back. The slirp DNS path to 127.0.0.11 must be confirmed in CI.
3. **Noise:** suppress sshj's `TransportImpl` ERROR during the readiness poll (logger
   `net.schmizz.sshj.transport.TransportImpl` to WARN inside the wait loop or via documentation).
4. **CI double runs:** add `concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }` or
   restrict `push` to `main`.
5. **UDP forwarding** for declared ports (`-p udp` DNAT rules, and slirp `udp::` hostfwd in user mode).
6. **Extra hardware** (blank virtio disks, second NIC) deferred from v1.
7. **RHEL drop-in** is by design but has not been exercised; the RHEL KVM guest image has default user
   `cloud-user`, which does not matter because the module creates its own user.
8. **Overlay location**: container writable layer today; switch to an anonymous volume if overlayfs write
   performance becomes a problem for installer-heavy tests.

---

## 12. Acceptance criteria for the final product

- `./gradlew build` passes with no Docker.
- `smoke-test.sh` passes against the current Rocky 9 GenericCloud image on a KVM host.
- All integration tests in section 7.2 pass in CI on a GitHub-hosted runner.
- A consumer project can depend on the published jar, point `VmContainer` at a qcow2, and run a test with
  nothing beyond a local Docker daemon and `/dev/kvm`.
- The README states the exact container grants (devices, capability, sysctls) and the failure messages a
  user sees when each one is missing.
