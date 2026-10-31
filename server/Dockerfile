# The server's container image (design 22 §6): ghcr.io/<owner>/handy-server, linux/amd64 and
# linux/arm64. It only COPYs the binary the release built natively for that architecture (static
# musl, from its tarball) - no compiler and no QEMU here. The build context is server/ with dist/
# laid out as (one architecture's binary is enough for a single-platform build):
#   dist/amd64/kid_phone_server  dist/arm64/kid_phone_server  dist/static/
# Base images are pinned by digest (multi-arch indexes); bump them deliberately.

# distroless static, nonroot: uid 65532, a CA bundle (the TLS stack reads it), tzdata, /tmp; no shell.
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab AS base

# data/ and data/keys/ owned by the image's user (uid 65532): a named volume is seeded from them.
FROM --platform=$BUILDPLATFORM busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e AS dirs
RUN mkdir -p /out/data/keys && chmod 700 /out/data/keys

# The CA bundle minus the roots deploy/distrusted-roots.txt names (2026-10-31, the eMudhra key
# compromise): the base follows Debian's removals only at its next rebuild, so the image removes
# them itself. Each PEM block's DER SHA-256 is compared with the list; the build fails if a listed
# root survives, if nothing is left to speak of, or if the bundle wasn't where the base keeps it.
FROM --platform=$BUILDPLATFORM busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e AS certs
COPY --from=base /etc/ssl/certs/ca-certificates.crt /in/ca-certificates.crt
COPY deploy/distrusted-roots.txt /in/distrusted-roots.txt
RUN set -eu; mkdir -p /work /out; cd /work; \
    awk 'BEGIN{n=0} /-----BEGIN CERTIFICATE-----/{n++; f=sprintf("cert-%04d.pem", n)} n>0{print > f} /-----END CERTIFICATE-----/{close(f)}' /in/ca-certificates.crt; \
    total=$(ls cert-*.pem | wc -l); [ "$total" -ge 100 ] || { echo "only $total certificates in the base bundle" >&2; exit 1; }; \
    grep -v '^#' /in/distrusted-roots.txt | grep -v '^[[:space:]]*$' | cut -d' ' -f1 | tr 'a-f' 'A-F' > /work/deny; \
    : > /out/ca-certificates.crt; kept=0; removed=0; \
    for f in cert-*.pem; do \
        fp=$(sed '/-----/d' "$f" | tr -d '\n' | base64 -d | sha256sum | cut -d' ' -f1 | tr 'a-f' 'A-F'); \
        if grep -qx "$fp" /work/deny; then removed=$((removed+1)); echo "removed $fp"; else cat "$f" >> /out/ca-certificates.crt; kept=$((kept+1)); fi; \
    done; \
    echo "ca bundle: $total in the base, $removed removed, $kept kept"; \
    [ "$kept" -ge 100 ] || { echo "too few certificates kept" >&2; exit 1; }; \
    for fp in $(cat /work/deny); do \
        awk 'BEGIN{n=0} /-----BEGIN CERTIFICATE-----/{n++; f=sprintf("check-%04d.pem", n)} n>0{print > f} /-----END CERTIFICATE-----/{close(f)}' /out/ca-certificates.crt; \
        for g in check-*.pem; do \
            [ "$(sed '/-----/d' "$g" | tr -d '\n' | base64 -d | sha256sum | cut -d' ' -f1 | tr 'a-f' 'A-F')" != "$fp" ] || { echo "distrusted root $fp is still in the bundle" >&2; exit 1; }; \
        done; \
    done; \
    chmod 644 /out/ca-certificates.crt

FROM base
ARG TARGETARCH
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="handy-server" \
      org.opencontainers.image.description="Admin server and device API for the handy kid's phone" \
      org.opencontainers.image.source="https://github.com/palchrb/vibb-launcher" \
      org.opencontainers.image.licenses="GPL-3.0-or-later" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
WORKDIR /app
COPY --from=dirs --chown=65532:65532 /out/data /app/data
COPY --from=certs /out/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY dist/${TARGETARCH}/kid_phone_server /app/kid_phone_server
COPY dist/static /app/static
# The admin listener stays on loopback (use network_mode: host, or publish 127.0.0.1:3100 only).
ENV BIND_ADDR=127.0.0.1:3100 DEPLOY_KIND=docker
USER 65532
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["/app/kid_phone_server", "healthcheck"]
ENTRYPOINT ["/app/kid_phone_server"]
