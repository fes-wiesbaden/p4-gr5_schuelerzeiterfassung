#!/bin/sh
set -eu

cert_dir=/tmp/tls
rm -rf "$cert_dir"

assert_san() {
  expected_san="$1"
  openssl x509 -in "$cert_dir/server.crt" -noout -ext subjectAltName \
    | sed -n '/Subject Alternative Name/{n;s/^[[:space:]]*//;p;}' \
    | tr ',' '\n' \
    | sed 's/^[[:space:]]*//' \
    | grep -Fxq "$expected_san"
}

CERT_DIR="$cert_dir" TLS_HOST=192.168.1.10 /usr/local/bin/generate-certificates
openssl verify -CAfile "$cert_dir/ca.crt" "$cert_dir/server.crt"
openssl verify -purpose sslclient -CAfile "$cert_dir/esp32-client-ca.crt" "$cert_dir/esp32-client.crt"
assert_san 'IP Address:192.168.1.10'
client_certificate_before=$(sha256sum "$cert_dir/esp32-client.crt")

CERT_DIR="$cert_dir" TLS_HOST=192.168.1.1 /usr/local/bin/generate-certificates
openssl verify -CAfile "$cert_dir/ca.crt" "$cert_dir/server.crt"
openssl verify -purpose sslclient -CAfile "$cert_dir/esp32-client-ca.crt" "$cert_dir/esp32-client.crt"
assert_san 'IP Address:192.168.1.1'
test "$client_certificate_before" = "$(sha256sum "$cert_dir/esp32-client.crt")"
