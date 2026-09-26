FROM golang:1.24-alpine AS build

ENV CGO_ENABLED=0
RUN apk add --no-cache ca-certificates git

# MinIO Community Edition is source-only now. Pin to the final community
# security release so production does not depend on an archived registry image.
RUN go install github.com/minio/minio@RELEASE.2025-10-15T17-29-55Z

FROM alpine:3.21

RUN apk add --no-cache ca-certificates tzdata \
    && adduser -D -u 10001 minio \
    && mkdir -p /data /home/minio \
    && chown -R minio:minio /data /home/minio

COPY --from=build /go/bin/minio /usr/local/bin/minio

USER minio
ENV HOME=/home/minio

EXPOSE 9000 9001

ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data"]
