# Pull base image.
FROM openjdk:8

VOLUME /tmp

RUN curl -sL https://rpm.nodesource.com/setup_10.x | bash -
RUN yum install -y nodejs
RUN npm install elasticdump -g
ENV SPRING_PROFILES_ACTIVE=prod
ENV ES_CONFIG_SYNONYM_DIR=/opt/bitnami/elasticsearch/config/
RUN ls -a
ADD rewayaat-1.0.jar app.jar
RUN /bin/sh -c 'touch /app.jar'
ENTRYPOINT ["java","-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005","-Djava.security.egd=file:/dev/./urandom","-jar","/app.jar"]