#!/bin/sh
set -eu
# Only container startup has these namespace-local capabilities. All tasks run as UID 1000.
/usr/sbin/iptables -P OUTPUT DROP
/usr/sbin/iptables -A OUTPUT -o lo -j ACCEPT
/usr/sbin/iptables -A OUTPUT -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
/usr/sbin/iptables -A OUTPUT -d 169.254.1.1 -p udp --dport 53 -j ACCEPT
/usr/sbin/iptables -A OUTPUT -d 169.254.1.1 -p tcp --dport 53 -j ACCEPT
for subnet in 0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 240.0.0.0/4; do
    /usr/sbin/iptables -A OUTPUT -d "$subnet" -j REJECT
done
for address in ${QBOT_HOST_IPV4:-}; do
    /usr/sbin/iptables -A OUTPUT -d "$address" -j REJECT
done
/usr/sbin/iptables -A OUTPUT -j ACCEPT
/usr/bin/touch /run/qbot-ready
exec /usr/bin/setpriv --reuid=1000 --regid=1000 --clear-groups --bounding-set=-all --inh-caps=-all --ambient-caps=-all /usr/bin/sleep infinity
