#!/bin/sh
set -eu

env_file="${1:-.env}"
tls_host=$(awk -F= '$1 == "TLS_HOST" { print $2; exit }' "$env_file")
ca_file=.local/tls/ca.crt
server_certificate=.local/tls/server.crt
client_ca_file=.local/tls/esp32-client-ca.crt
client_certificate=.local/tls/esp32-client.crt
client_key=.local/tls/esp32-client.key

openssl verify -CAfile "$ca_file" "$server_certificate"
openssl verify -verify_ip "$tls_host" -CAfile "$ca_file" "$server_certificate"
openssl verify -CAfile "$client_ca_file" "$client_certificate"
curl --fail --silent --show-error --cacert "$ca_file" "https://$tls_host:8443/terminal/1" \
  | grep -Fq 'id="app"'

if curl --fail --silent --show-error --cacert "$ca_file" \
  "https://$tls_host:8444/api/actuator/health" >/dev/null; then
  echo 'mTLS endpoint unexpectedly accepted a request without client certificate' >&2
  exit 1
fi

curl --fail --silent --show-error --cacert "$ca_file" --cert "$client_certificate" --key "$client_key" \
  "https://$tls_host:8444/api/actuator/health"

if curl --connect-timeout 2 --fail --silent "http://$tls_host:8443/terminal/1" >/dev/null; then
  echo 'HTTP unexpectedly reachable' >&2
  exit 1
fi

backend_port=$(docker compose --env-file "$env_file" port backend 8080 2>/dev/null || true)
if printf '%s' "$backend_port" | grep -Eq ':[1-9][0-9]*$'; then
  echo 'Backend port unexpectedly published' >&2
  exit 1
fi

mysql_port=$(docker compose --env-file "$env_file" port mysql 3306 2>/dev/null || true)
if printf '%s' "$mysql_port" | grep -Eq ':[1-9][0-9]*$'; then
  echo 'MySQL port unexpectedly published' >&2
  exit 1
fi
