#!/bin/bash
# Boots the runner image with plain docker (no Java) and checks the guest comes up.
#   usage: smoke-test.sh /path/to/guest.qcow2 [image-tag]
# Needs: docker, /dev/kvm, ssh, ssh-keygen. Exits non-zero on failure.
set -euo pipefail

IMAGE_PATH="${1:?path to qcow2}"
TAG="${2:-vm-testcontainers-qemu:dev}"
WORK="$(mktemp -d)"
NAME="vmtc-smoke-$$"
trap 'docker rm -f "$NAME" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

ssh-keygen -q -t ed25519 -N '' -f "$WORK/id"
PUB="$(cat "$WORK/id.pub")"
mkdir -p "$WORK/seed"
cat > "$WORK/seed/user-data" <<UD
#cloud-config
users:
  - default
  - name: tc
    sudo: ALL=(ALL) NOPASSWD:ALL
    shell: /bin/bash
    ssh_authorized_keys:
      - $PUB
UD
printf 'instance-id: smoke-%s\nlocal-hostname: smoke\n' "$$" > "$WORK/seed/meta-data"

docker run -d --name "$NAME" \
  --device /dev/kvm --device /dev/net/tun --cap-add NET_ADMIN \
  -v "$(realpath "$IMAGE_PATH"):/images/base.qcow2:ro" \
  -v "$WORK/seed:/vm/seed:ro" \
  -e VM_SEED_DIR=/vm/seed \
  -p 127.0.0.1::22 \
  "$TAG" >/dev/null
PORT="$(docker port "$NAME" 22/tcp | head -1 | sed 's/.*://')"
echo "container $NAME up, ssh on 127.0.0.1:$PORT; waiting for guest..."

SSH=(ssh -i "$WORK/id" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=3 -o LogLevel=ERROR -p "$PORT" tc@127.0.0.1)
for i in $(seq 1 120); do
  if "${SSH[@]}" true 2>/dev/null; then
    echo "ssh up after ~$((i*3))s"
    break
  fi
  if ! docker ps -q --no-trunc | grep -q "$(docker inspect -f '{{.Id}}' "$NAME")"; then
    echo "container exited early:"; docker logs "$NAME" | tail -50; exit 1
  fi
  sleep 3
done
"${SSH[@]}" true || { echo "guest never became reachable"; docker logs "$NAME" | tail -80; exit 1; }

"${SSH[@]}" sudo cloud-init status --wait
"${SSH[@]}" 'sudo id -u | grep -qx 0 && echo "sudo ok"; getenforce; cat /etc/os-release | head -2'
"${SSH[@]}" 'getent hosts host.docker.internal >/dev/null 2>&1 || true; curl -sSf -m 10 -o /dev/null https://dl.rockylinux.org/ && echo "outbound ok" || echo "outbound FAILED (may be expected offline)"'

echo "requesting ACPI power off..."
docker exec "$NAME" vm-shutdown 40
docker logs "$NAME" 2>&1 | grep -E "reboot: Power down|Power down" && echo "graceful power-off observed"
echo "SMOKE TEST PASSED"
