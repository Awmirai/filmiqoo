FROM golang:1.25-alpine3.21 AS builder
RUN apk add --no-cache git
WORKDIR /src
RUN git clone https://github.com/EverythingSuckz/TG-FileStreamBot.git . \
 && git checkout 6273377bc0a8dcf14a1ec415d9081581ae363b34
COPY filmiqoo.patch /tmp/filmiqoo.patch
RUN git apply /tmp/filmiqoo.patch
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-w -s" -o /out/fsb ./cmd/fsb

FROM scratch
COPY --from=builder /out/fsb /app/fsb
EXPOSE 8081
ENTRYPOINT ["/app/fsb", "run"]
