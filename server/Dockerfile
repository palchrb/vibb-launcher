# The server's container image (design 22 §6): ghcr.io/<owner>/handy-server, linux/amd64 and
# linux/arm64. It only COPYs binaries the release job cross-built (static musl) - no compiler and no
# QEMU here. The build context is server/ with dist/ laid out as:
#   dist/amd64/kid_phone_server  dist/arm64/kid_phone_server  dist/static/
# Base images are pinned by digest (multi-arch indexes); bump them deliberately.

# data/ and data/keys/ owned by the image's user (uid 65532): a named volume is seeded from them.
FROM --platform=$BUILDPLATFORM busybox:1.37@sha256:bdf57e528e45e4433820e045b29b4597825a1c9e38353532d90a01445013f82e AS dirs
RUN mkdir -p /out/data/keys && chmod 700 /out/data/keys

# distroless static, nonroot: uid 65532, a CA bundle (the TLS stack reads it), tzdata, /tmp; no shell.
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
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
COPY dist/${TARGETARCH}/kid_phone_server /app/kid_phone_server
COPY dist/static /app/static
# The admin listener stays on loopback (use network_mode: host, or publish 127.0.0.1:3100 only).
ENV BIND_ADDR=127.0.0.1:3100 DEPLOY_KIND=docker
USER 65532
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s CMD ["/app/kid_phone_server", "healthcheck"]
ENTRYPOINT ["/app/kid_phone_server"]
