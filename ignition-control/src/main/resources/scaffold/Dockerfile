# Starter — replace with your app. Every app is deployed listening on 8080
# (also handed to the container as $PORT, if your app prefers to read it) —
# not configurable, so there's nothing to keep in sync.
FROM nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
