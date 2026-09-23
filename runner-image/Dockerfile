# Runner image for vm-testcontainers: boots a guest qcow2 under QEMU/KVM.
#
# Required at `docker run` time (VmContainer adds these for you):
#   --device /dev/kvm --device /dev/net/tun --cap-add NET_ADMIN
#   -v /path/to/guest.qcow2:/images/base.qcow2:ro
#
# Contract (environment variables) is documented in vm-run.sh.
FROM debian:bookworm-slim

RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        qemu-system-x86 \
        qemu-utils \
        seabios \
        ipxe-qemu \
        iproute2 \
        iptables \
        dnsmasq \
        genisoimage \
        socat \
        procps \
        ca-certificates \
 && rm -rf /var/lib/apt/lists/*

COPY vm-run.sh vm-net-up.sh vm-shutdown /usr/local/bin/
RUN chmod 0755 /usr/local/bin/vm-run.sh /usr/local/bin/vm-net-up.sh /usr/local/bin/vm-shutdown \
 && mkdir -p /vm/seed /images

WORKDIR /vm
# Port 22 is always forwarded to the guest's sshd; declared VM_PORTS are added at runtime.
EXPOSE 22
ENTRYPOINT ["/usr/local/bin/vm-run.sh"]
