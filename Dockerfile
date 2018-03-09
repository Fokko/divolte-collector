# Divolte
#
# Divolte Documentation:
# www.divolte.io
#

FROM centos

#
# Configuration
#
ENV MAVEN_VERSION 3.2.5
ENV DIVOLTE_VERSION 0.8.0-SNAPSHOT
ENV DIVOLTE_DIR /opt/divolte/divolte-collector/

ENV BUILD_DEPS "autoconf automake libtool make zlib-devel wget curl-devel git"

ENV JAVA_HOME /usr/lib/jvm/java-openjdk/

ADD . /divolte/

# Some maven
RUN yum install -y java-1.8.0-openjdk-devel $BUILD_DEPS \
 && wget -q -O - http://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz | tar -xzf - -C /usr/local \
 && ln -s /usr/local/apache-maven-${MAVEN_VERSION} /usr/local/apache-maven \
 && ln -s /usr/local/apache-maven/bin/mvn /usr/local/bin/mvn \
 && git clone https://github.com/divolte/divolte-schema.git /tmp/divolte-schema \
 && cd /tmp/divolte-schema/ \
 && mvn --batch-mode --quiet install \
 && cd /divolte/ \
 && ./gradlew build -x test \
 && mv /divolte/build/libs/divolte-collector-${DIVOLTE_VERSION}-all.jar /tmp/divolte.jar \
 && yum remove -y $BUILD_DEPS \
 && yum clean all \
 && rm -rf /root/.gradle \
 && rm -rf /root/.m2 \
 && rm -rf /tmp/divolte-schema/ \
 && rm -rf /var/cache/yum \
 && rm -rf /divolte/

# Make the layer unique
RUN head -n 22 /dev/urandom > /tmp/vo

WORKDIR /divolte/

EXPOSE 8290

ENV JAVA_OPTS "-XX:+UseG1GC -Djava.awt.headless=true"

COPY conf/ /etc/divolte/conf/

WORKDIR ${DIVOLTE_DIR}

CMD java -jar ${JAVA_OPTS} -Dconfig.file=/etc/divolte/conf/divolte-collector.conf /tmp/divolte.jar
