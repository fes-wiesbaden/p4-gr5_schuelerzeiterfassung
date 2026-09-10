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
openssl verify -purpose sslclient -CAfile "$cert_dir/terminal-client-ca.crt" \
  "$cert_dir/terminal-23102003-client.crt"
openssl x509 -in "$cert_dir/terminal-23102003-client.crt" -noout -subject \
  | grep -Fq 'CN = terminal-23102003'
openssl pkcs12 -in "$cert_dir/terminal-23102003-client.p12" -passin pass:changeit -noout
keytool -list -storetype PKCS12 -keystore "$cert_dir/terminal-server-truststore.p12" \
  -storepass changeit -alias attendance-server-ca
assert_san 'IP Address:192.168.1.10'
client_certificate_before=$(sha256sum "$cert_dir/terminal-23102003-client.crt")

CERT_DIR="$cert_dir" TLS_HOST=192.168.1.1 /usr/local/bin/generate-certificates
openssl verify -CAfile "$cert_dir/ca.crt" "$cert_dir/server.crt"
openssl verify -purpose sslclient -CAfile "$cert_dir/terminal-client-ca.crt" \
  "$cert_dir/terminal-23102003-client.crt"
assert_san 'IP Address:192.168.1.1'
test "$client_certificate_before" = "$(sha256sum "$cert_dir/terminal-23102003-client.crt")"
