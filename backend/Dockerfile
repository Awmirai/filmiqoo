FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN go mod tidy && go mod verify
ARG GEO_DATA_MONTH
RUN go run ./cmd/countrydata -month "$GEO_DATA_MONTH" -out /out/iran-country.json
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/filmiqoo-api ./cmd/api

FROM alpine:3.21
RUN adduser -D -H -u 10001 filmiqoo
USER filmiqoo
COPY --from=build /out/filmiqoo-api /filmiqoo-api
COPY --from=build /out/iran-country.json /geo/iran-country.json
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/readyz || exit 1
ENTRYPOINT ["/filmiqoo-api"]
