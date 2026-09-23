#!/bin/bash
# Entrypoint: preflight, create overlay disk, build cloud-init seed, bring up
# networking, then exec QEMU in the foreground with the serial console on stdout.
#
# Environment contract:
#   VM_IMAGE      read-only base qcow2 (default /images/base.qcow2)
#   VM_CPUS       vCPUs (default 2)
#   VM_MEM_MB     RAM in MiB (default 2048)
#   VM_DISK_GB    if set, resize the overlay to this many GiB (cloud-init growpart expands /)
#   VM_ACCEL      kvm (default) or tcg
#   VM_PORTS      comma-separated guest TCP ports to DNAT from eth0 (22 is always added)
#   VM_SUBNET     tap subnet, default 10.200.0.0/24 (container .1, guest .2)
#   VM_SEED_DIR   directory with cloud-init user-data/meta-data (default /vm/seed); ignored if empty
#   VM_QEMU_EXTRA_ARGS  extra args appended verbatim to the qemu command line (advanced)
set -euo pipefail

VM_IMAGE="${VM_IMAGE:-/images/base.qcow2}"
VM_CPUS="${VM_CPUS:-2}"
VM_MEM_MB="${VM_MEM_MB:-2048}"
VM_DISK_GB="${VM_DISK_GB:-}"
VM_ACCEL="${VM_ACCEL:-kvm}"
VM_PORTS="${VM_PORTS:-}"
VM_SUBNET="${VM_SUBNET:-10.200.0.0/24}"
VM_SEED_DIR="${VM_SEED_DIR:-/vm/seed}"
VM_QEMU_EXTRA_ARGS="${VM_QEMU_EXTRA_ARGS:-}"

OVERLAY=/vm/overlay.qcow2
SEED_ISO=/vm/seed.iso
QMP_SOCK=/vm/qmp.sock
GUEST_MAC="52:54:00:c0:ff:ee"

log() { echo "[vm-run] $*" >&2; }
die() { echo "[vm-run] ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------- preflight
[[ -r "$VM_IMAGE" ]] || die "guest image not found or unreadable at $VM_IMAGE (bind-mount your qcow2 there, read-only is fine)"

case "$VM_ACCEL" in
  kvm)
    [[ -e /dev/kvm ]] || die "/dev/kvm is not present in the container. Run with --device /dev/kvm (host needs virtualization enabled), or set VM_ACCEL=tcg for slow software emulation."
    [[ -r /dev/kvm && -w /dev/kvm ]] || die "/dev/kvm exists but is not readable/writable. On the host: sudo usermod -aG kvm \$USER (or chmod 666 /dev/kvm on CI runners)."
    ;;
  tcg)
    log "software emulation (TCG) requested: expect boots measured in minutes, not seconds"
    ;;
  *) die "VM_ACCEL must be 'kvm' or 'tcg', got '$VM_ACCEL'" ;;
esac

[[ -e /dev/net/tun ]] || die "/dev/net/tun is not present. Run with --device /dev/net/tun."
[[ "$VM_CPUS" =~ ^[0-9]+$ && "$VM_CPUS" -ge 1 ]] || die "VM_CPUS must be a positive integer"
[[ "$VM_MEM_MB" =~ ^[0-9]+$ && "$VM_MEM_MB" -ge 256 ]] || die "VM_MEM_MB must be an integer >= 256"
[[ -z "$VM_DISK_GB" || "$VM_DISK_GB" =~ ^[0-9]+$ ]] || die "VM_DISK_GB must be an integer number of GiB"

# ------------------------------------------------------------------ overlay
# The base image is never written to: every boot gets a fresh copy-on-write layer.
rm -f "$OVERLAY"
qemu-img create -q -f qcow2 -b "$VM_IMAGE" -F qcow2 "$OVERLAY"
if [[ -n "$VM_DISK_GB" ]]; then
  qemu-img resize -q "$OVERLAY" "${VM_DISK_GB}G"
fi
log "overlay created on $(qemu-img info --output=json "$VM_IMAGE" | sed -n 's/.*"virtual-size": \([0-9]*\).*/\1/p' | awk '{printf "%.1f GiB base", $1/1024/1024/1024}')${VM_DISK_GB:+, resized to ${VM_DISK_GB} GiB}"

# ---------------------------------------------------------------- seed iso
SEED_ARGS=()
if [[ -s "$VM_SEED_DIR/user-data" ]]; then
  [[ -s "$VM_SEED_DIR/meta-data" ]] || printf 'instance-id: vm-testcontainers\n' > "$VM_SEED_DIR/meta-data"
  genisoimage -quiet -output "$SEED_ISO" -volid cidata -joliet -rock "$VM_SEED_DIR/user-data" "$VM_SEED_DIR/meta-data" \
    $( [[ -s "$VM_SEED_DIR/network-config" ]] && echo "$VM_SEED_DIR/network-config" )
  SEED_ARGS=(-drive "file=$SEED_ISO,media=cdrom,format=raw,read-only=on")
  log "cloud-init NoCloud seed attached"
else
  log "no cloud-init seed (VM_SEED_DIR=$VM_SEED_DIR has no user-data); booting image as-is"
fi

# --------------------------------------------------------------- networking
GUEST_IP="$(/usr/local/bin/vm-net-up.sh "$VM_SUBNET" "$GUEST_MAC" "$VM_PORTS")"
log "guest will get $GUEST_IP via DHCP; forwarded TCP ports: 22${VM_PORTS:+,$VM_PORTS}"

# --------------------------------------------------------------------- qemu
if [[ "$VM_ACCEL" == "kvm" ]]; then
  MACHINE_ARGS=(-machine q35,accel=kvm -cpu host)
else
  MACHINE_ARGS=(-machine q35,accel=tcg -cpu max)
fi

rm -f "$QMP_SOCK"
log "starting qemu: cpus=$VM_CPUS mem=${VM_MEM_MB}M accel=$VM_ACCEL"
# shellcheck disable=SC2086
exec qemu-system-x86_64 \
  "${MACHINE_ARGS[@]}" \
  -smp "$VM_CPUS" \
  -m "${VM_MEM_MB}M" \
  -drive "file=$OVERLAY,if=virtio,format=qcow2,cache=unsafe,discard=unmap" \
  "${SEED_ARGS[@]}" \
  -netdev "tap,id=net0,ifname=tap0,script=no,downscript=no" \
  -device "virtio-net-pci,netdev=net0,mac=$GUEST_MAC" \
  -device virtio-rng-pci \
  -display none \
  -monitor none \
  -serial stdio \
  -qmp "unix:$QMP_SOCK,server,nowait" \
  $VM_QEMU_EXTRA_ARGS
