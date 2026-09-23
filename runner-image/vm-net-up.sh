#!/bin/bash
# Bring up guest networking inside the container namespace.
#   usage: vm-net-up.sh <subnet/cidr> <guest-mac> <comma-separated-tcp-ports>
# Prints the guest IP on stdout.
#
# Layout:  guest <-virtio-net-> tap0 (container, .1) <-NAT-> eth0 (Docker network)
#   * outbound from guest: MASQUERADE through eth0, so the guest reaches other containers by alias
#   * DNS: dnsmasq on tap0 forwards to the container's resolvers (Docker's embedded DNS on user networks)
#   * inbound: DNAT on eth0 for port 22 and each declared port, so other containers reach the
#     guest via the VM container's hostname, and Docker's -p mapping reaches it from the host
set -euo pipefail

SUBNET="${1:?subnet}"
GUEST_MAC="${2:?guest mac}"
PORTS="${3:-}"

NET="${SUBNET%/*}"
PREFIX="${SUBNET#*/}"
BASE="${NET%.*}"          # first three octets; only /24-ish subnets are supported
GW_IP="$BASE.1"
GUEST_IP="$BASE.2"

ip tuntap add dev tap0 mode tap 2>/dev/null || {
  echo "[vm-net] ERROR: cannot create tap0. The container needs --cap-add NET_ADMIN and --device /dev/net/tun." >&2
  exit 1
}
ip addr add "$GW_IP/$PREFIX" dev tap0
ip link set tap0 up

sysctl -q -w net.ipv4.ip_forward=1

# Outbound NAT for the guest.
iptables -t nat -A POSTROUTING -s "$SUBNET" ! -o tap0 -j MASQUERADE
# Traffic from this container itself (docker exec) that was DNATed to the guest needs a routable source.
iptables -t nat -A POSTROUTING -o tap0 -s 127.0.0.0/8 -j MASQUERADE
iptables -A FORWARD -i tap0 -j ACCEPT
iptables -A FORWARD -o tap0 -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT

# Inbound DNAT for sshd and declared ports. "! -i tap0" so guest-originated traffic is untouched.
IFS=',' read -r -a PORT_LIST <<< "22${PORTS:+,$PORTS}"
for p in "${PORT_LIST[@]}"; do
  p="${p//[[:space:]]/}"
  [[ -z "$p" ]] && continue
  [[ "$p" =~ ^[0-9]+$ ]] || { echo "[vm-net] ERROR: bad port '$p' in VM_PORTS" >&2; exit 1; }
  iptables -t nat -A PREROUTING ! -i tap0 -p tcp --dport "$p" -j DNAT --to-destination "$GUEST_IP:$p"
  # Also let processes inside this container (docker exec, smoke tests) reach the guest via localhost.
  iptables -t nat -A OUTPUT -o lo -p tcp --dport "$p" -j DNAT --to-destination "$GUEST_IP:$p"
done
sysctl -q -w net.ipv4.conf.all.route_localnet=1

# DHCP (single fixed lease) + DNS forwarding for the guest.
dnsmasq \
  --interface=tap0 --bind-interfaces --except-interface=lo \
  --dhcp-range="$GUEST_IP,$GUEST_IP,12h" \
  --dhcp-host="$GUEST_MAC,$GUEST_IP" \
  --dhcp-option=option:router,"$GW_IP" \
  --dhcp-option=option:dns-server,"$GW_IP" \
  --dhcp-authoritative \
  --resolv-file=/etc/resolv.conf \
  --no-hosts \
  --log-facility=/vm/dnsmasq.log \
  --pid-file=/vm/dnsmasq.pid

echo "$GUEST_IP"
