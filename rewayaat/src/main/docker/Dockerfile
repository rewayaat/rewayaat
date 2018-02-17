# Pull base image.
FROM openjdk:8

VOLUME /tmp

RUN wget --no-check-certificate https://nodejs.org/dist/v4.2.1/node-v4.2.1-linux-x64.tar.gz
RUN tar --strip-components 1 -xzf node-v4.2.1-linux-x64.tar.gz
RUN npm install elasticdump -g
ENV SPRING_PROFILES_ACTIVE=prod
ENV ES_CONFIG_SYNONYM_FILE=/opt/bitnami/elasticsearch/config/synonyms.txt
RUN ls -a
ADD rewayaat-1.0.jar app.jar
RUN /bin/sh -c 'touch /app.jar'
ENTRYPOINT ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]