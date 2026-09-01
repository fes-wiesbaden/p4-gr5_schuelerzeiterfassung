#!/bin/sh
set -eu

env_file="${1:-.env}"
tls_host=$(awk -F= '$1 == "TLS_HOST" { print $2; exit }' "$env_file")
ca_file=.local/tls/ca.crt
server_certificate=.local/tls/server.crt

openssl verify -CAfile "$ca_file" "$server_certificate"
openssl verify -verify_ip "$tls_host" -CAfile "$ca_file" "$server_certificate"
curl --fail --silent --show-error --cacert "$ca_file" "https://$tls_host:8443/api/actuator/health"
curl --fail --silent --show-error --cacert "$ca_file" "https://$tls_host:8443/terminal/1" \
  | grep -Fq 'Bereit für die Erfassung'

if curl --connect-timeout 2 --fail --silent "http://$tls_host:8443/terminal/1" >/dev/null; then
  echo 'HTTP unexpectedly reachable' >&2
  exit 1
fi

backend_port=$(docker compose --env-file "$env_file" port backend 8080 2>/dev/null || true)
if [ -n "$backend_port" ]; then
  echo 'Backend port unexpectedly published' >&2
  exit 1
fi

mysql_port=$(docker compose --env-file "$env_file" port mysql 3306 2>/dev/null || true)
if [ -n "$mysql_port" ]; then
  echo 'MySQL port unexpectedly published' >&2
  exit 1
fi
