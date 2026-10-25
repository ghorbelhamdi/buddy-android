# Buddy's phone tools over stdio (see README.md). In a container there's no phone, so the bridge lists Buddy's tools
# and each call says Buddy isn't reachable; on the phone (Termux), run bridge.mjs directly instead.
FROM node:22-alpine
WORKDIR /app
COPY bridge.mjs tools.json ./
ENTRYPOINT ["node", "bridge.mjs"]
