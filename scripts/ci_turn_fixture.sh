#!/usr/bin/env bash
# Ephemeral authenticated TURN, LOOPBACK ONLY. Never a production service.
set -Eeuo pipefail
[[ "${CI:-}" == true && -n "${RUNNER_TEMP:-}" ]] || { echo 'FAIL: isolated CI runner required'; exit 1; }
root="$RUNNER_TEMP/lanu-turn-fixture"
case "${1:-}" in
  start)
    umask 077
    mkdir -p "$root/package"
    [[ ! -f "$root/pid" ]] || { echo 'FAIL: fixture already started'; exit 1; }
    (
      cd "$root"
      # Signed Ubuntu package metadata verifies the pinned test-only binary.
      apt-get download 'coturn=4.5.2-3.1~ubuntu22.04.1'
      mapfile -t packages < <(find . -maxdepth 1 -name 'coturn_*.deb' -type f)
      [[ "${#packages[@]}" == 1 ]]
      dpkg-deb -x "${packages[0]}" package
    )
    password="$(openssl rand -hex 24)"
    cli_password="$(openssl rand -hex 24)"
    echo "::add-mask::$password"
    echo "::add-mask::$cli_password"
    cat > "$root/turnserver.conf" <<CONF
listening-ip=127.0.0.1
relay-ip=127.0.0.1
listening-port=3478
min-port=49160
max-port=49220
lt-cred-mech
realm=lanu-ci.invalid
user=lanu-ci:$password
user-quota=32
total-quota=32
max-allocate-lifetime=120
max-bps=64000
bps-capacity=512000
no-cli
cli-password=$cli_password
no-tls
no-dtls
no-tcp-relay
no-multicast-peers
allow-loopback-peers
no-stdout-log
log-file=/dev/null
pidfile=$root/server.pid
CONF
    # A second TCP-only listener proves the application's real UDP->TCP fallback.
    sed -e 's/listening-port=3478/listening-port=3479/' \
        -e 's/min-port=49160/min-port=49300/' -e 's/max-port=49220/max-port=49360/' \
        -e 's@/server.pid@/server-tcp.pid@' "$root/turnserver.conf" > "$root/turnserver-tcp.conf"
    printf '%s\n' 'no-udp' >> "$root/turnserver-tcp.conf"
    nohup "$root/package/usr/bin/turnserver" -c "$root/turnserver-tcp.conf" > /dev/null 2>&1 < /dev/null &
    echo "$!" > "$root/pid-tcp"
    # No maintainer scripts or system service are installed/started.
    nohup "$root/package/usr/bin/turnserver" -c "$root/turnserver.conf" > /dev/null 2>&1 < /dev/null &
    echo "$!" > "$root/pid"
    for attempt in {1..30}; do
      kill -0 "$(cat "$root/pid-tcp")" 2>/dev/null || { echo 'FAIL: TCP-only TURN fixture stopped'; exit 1; }
      kill -0 "$(cat "$root/pid")" 2>/dev/null || { echo 'FAIL: local TURN fixture stopped'; exit 1; }
      if python3 -c 'import socket; s=socket.create_connection(("127.0.0.1",3478),.5); s.close(); s=socket.create_connection(("127.0.0.1",3479),.5); s.close()' 2>/dev/null; then
        printf 'LANU_TURN_TEST_USER=lanu-ci\nLANU_TURN_TEST_PASSWORD=%s\n' "$password" >> "$GITHUB_ENV"
        echo 'PASS: authenticated loopback TURN fixture ready'
        exit 0
      fi
      sleep 1
    done
    echo 'FAIL: local TURN fixture deadline'; exit 1
    ;;
  stop)
    for pidfile in "$root/pid" "$root/pid-tcp"; do
      if [[ -f "$pidfile" ]]; then
        pid="$(cat "$pidfile")"
        if [[ "$pid" =~ ^[0-9]+$ ]] && [[ -r "/proc/$pid/cmdline" ]] &&
           tr '\0' ' ' < "/proc/$pid/cmdline" | grep -Fq "$root/package/usr/bin/turnserver"; then
          kill "$pid" 2>/dev/null || true
        fi
        rm -f "$pidfile"
      fi
    done
    rm -f "$root/turnserver.conf" "$root/turnserver-tcp.conf"
    echo 'PASS: local TURN fixture retired'
    ;;
  *) echo 'usage: ci_turn_fixture.sh start|stop'; exit 2 ;;
esac
