#!/bin/sh
set -eu

cert_dir="${CERT_DIR:-/certs}"
host="${TLS_HOST:?TLS_HOST must be set}"
host_uid="${HOST_UID:-1000}"
host_gid="${HOST_GID:-1000}"
mkdir -p "$cert_dir"

if [ ! -f "$cert_dir/ca.key" ]; then
  openssl genrsa -out "$cert_dir/ca.key" 4096
  openssl req -x509 -new -key "$cert_dir/ca.key" -sha256 -days 3650 \
    -subj "/CN=Attendance Local Development CA" -out "$cert_dir/ca.crt"
fi

if [ ! -f "$cert_dir/esp32-client-ca.crt" ]; then
  openssl genrsa -out "$cert_dir/esp32-client-ca.key" 4096
  openssl req -x509 -new -key "$cert_dir/esp32-client-ca.key" -sha256 -days 3650 \
    -subj "/CN=Attendance ESP32 Client CA" -out "$cert_dir/esp32-client-ca.crt"
  openssl req -new -newkey rsa:2048 -nodes -keyout "$cert_dir/esp32-client.key" \
    -subj "/CN=Attendance ESP32" -out "$cert_dir/esp32-client.csr"
  printf '%s\n' \
    'basicConstraints = critical,CA:FALSE' \
    'keyUsage = critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage = clientAuth' > "$cert_dir/esp32-client.ext"
  openssl x509 -req -in "$cert_dir/esp32-client.csr" -CA "$cert_dir/esp32-client-ca.crt" \
    -CAkey "$cert_dir/esp32-client-ca.key" -CAcreateserial -out "$cert_dir/esp32-client.crt" \
    -days 3650 -sha256 -extfile "$cert_dir/esp32-client.ext"
  rm "$cert_dir/esp32-client.csr" "$cert_dir/esp32-client.ext"
fi

if printf '%s' "$host" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}$'; then
  subject_alt_name="IP:$host"
  expected_san="IP Address:$host"
else
  subject_alt_name="DNS:$host"
  expected_san="DNS:$host"
fi

if [ -f "$cert_dir/server.crt" ] \
  && openssl x509 -in "$cert_dir/server.crt" -noout -ext subjectAltName \
    | sed -n '/Subject Alternative Name/{n;s/^[[:space:]]*//;p;}' \
    | tr ',' '\n' \
    | sed 's/^[[:space:]]*//' \
    | grep -Fxq "$expected_san"; then
  :
else
  openssl req -new -newkey rsa:2048 -nodes -keyout "$cert_dir/server.key" \
    -subj "/CN=$host" -out "$cert_dir/server.csr"
  printf '%s\n' \
    'basicConstraints = critical,CA:FALSE' \
    'keyUsage = critical,digitalSignature,keyEncipherment' \
    'extendedKeyUsage = serverAuth' \
    "subjectAltName = $subject_alt_name" > "$cert_dir/server.ext"
  openssl x509 -req -in "$cert_dir/server.csr" -CA "$cert_dir/ca.crt" -CAkey "$cert_dir/ca.key" \
    -CAcreateserial -out "$cert_dir/server.crt" -days 365 -sha256 -extfile "$cert_dir/server.ext"
  rm "$cert_dir/server.csr" "$cert_dir/server.ext"
fi

chmod 600 "$cert_dir"/*.key
chmod 644 "$cert_dir"/*.crt
chown "$host_uid:$host_gid" "$cert_dir"/*
