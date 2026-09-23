FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/filmiqoo-api ./cmd/api

FROM alpine:3.21
RUN adduser -D -H -u 10001 filmiqoo
USER filmiqoo
COPY --from=build /out/filmiqoo-api /filmiqoo-api
EXPOSE 8080
ENTRYPOINT ["/filmiqoo-api"]
