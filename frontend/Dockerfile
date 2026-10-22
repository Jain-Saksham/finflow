# syntax=docker/dockerfile:1.7
# Static assets are platform-independent: build once on the native builder, even for arm64 images.
FROM --platform=$BUILDPLATFORM node:22.23.4-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Empty = same origin: nginx below proxies /api to the backend.
ARG VITE_API_BASE_URL=
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# Unprivileged nginx (runs as uid 101, listens on 8080).
FROM nginxinc/nginx-unprivileged:1.30.6-alpine
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# Where the API lives; rendered into the nginx config at container start.
ENV BACKEND_URL=http://backend:8080 \
    TRUSTED_PROXY_CIDR=127.0.0.1/32
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
