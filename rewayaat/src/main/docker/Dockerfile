# Pull base image.
FROM openjdk:8

VOLUME /tmp
ENV SPRING_PROFILES_ACTIVE=prod
RUN ls -a
ADD rewayaat-1.0.jar app.jar
RUN /bin/sh -c 'touch /app.jar'
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]