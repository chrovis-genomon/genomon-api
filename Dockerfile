FROM clojure:openjdk-11-lein-2.9.1-slim-buster AS builder
WORKDIR /root/genomon-api
COPY project.clj /root/genomon-api/project.clj
RUN lein -U deps
COPY . /root/genomon-api
RUN lein uberjar

FROM openjdk:11.0.6-jre-slim-buster AS genomon-api
ARG GIT_COMMIT
LABEL maintainer="Xcoo, Inc. <developer@xcoo.jp>"
LABEL GIT_COMMIT=${GIT_COMMIT}
ENV GIT_COMMIT=${GIT_COMMIT}
COPY --from=builder \
  /root/genomon-api/target/genomon-api-0.1.4-SNAPSHOT-standalone.jar \
  /usr/share/genomon-api/genomon-api.jar
COPY docker/genomon-api /usr/local/bin/genomon-api
COPY resources/genomon_api/ /etc/genomon-api/genomon_api/
ENTRYPOINT ["genomon-api"]
ENV PORT 80
EXPOSE 80
