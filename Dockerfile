# Tag Arena has no npm dependencies, so this stays tiny and builds in seconds.
FROM node:22-alpine

WORKDIR /app
COPY package.json server.js client.html ./

# Most hosts inject their own PORT; this is just the fallback.
ENV PORT=8080
EXPOSE 8080

# The server writes share/tag.html on boot; give it somewhere to put it.
RUN mkdir -p /app/share

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s \
  CMD wget -qO- http://127.0.0.1:${PORT}/health || exit 1

CMD ["node", "server.js"]
