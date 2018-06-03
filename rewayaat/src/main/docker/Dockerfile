FROM node:6
RUN npm install elasticdump -g

FROM openjdk:8
VOLUME /tmp
ENV SPRING_PROFILES_ACTIVE=prod
ENV ES_CONFIG_SYNONYM_DIR=/opt/bitnami/elasticsearch/config/
RUN ls -a
ADD rewayaat-1.0.jar app.jar
RUN /bin/sh -c 'touch /app.jar'
ENTRYPOINT ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]