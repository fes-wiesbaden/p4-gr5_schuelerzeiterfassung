FROM alpine:3.21

RUN apk add --no-cache openssl

COPY generate-certificates.sh /usr/local/bin/generate-certificates
COPY test-generate-certificates.sh /usr/local/bin/test-generate-certificates
RUN chmod 755 /usr/local/bin/generate-certificates /usr/local/bin/test-generate-certificates

ENTRYPOINT ["/usr/local/bin/generate-certificates"]
