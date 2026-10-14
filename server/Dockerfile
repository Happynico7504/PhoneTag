# Build from the repository root:
#   docker build -f server/Dockerfile -t phonetag-server .
#   docker run -p 47474:47474 phonetag-server
FROM gradle:8.14.3-jdk17 AS build
WORKDIR /src
ENV PHONETAG_SERVER_ONLY=1
COPY . .
RUN gradle --no-daemon :server:installDist

FROM eclipse-temurin:17-jre
COPY --from=build /src/server/build/install/phonetag-server /opt/phonetag-server
EXPOSE 47474
ENTRYPOINT ["/opt/phonetag-server/bin/phonetag-server"]
