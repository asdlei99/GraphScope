ARG REGISTRY=registry.cn-hongkong.aliyuncs.com
ARG BUILDER_VERSION=latest
FROM $REGISTRY/graphscope/graphscope-dev:$BUILDER_VERSION as builder

ARG CI=false

ARG profile=debug
ENV profile=$profile

COPY --chown=graphscope:graphscope . /home/graphscope/graphscope

COPY --chown=graphscope:graphscope ./interactive_engine/assembly/src/conf/maven.settings.xml /home/graphscope/.m2/settings.xml

USER graphscope

RUN cd /home/graphscope/graphscope \
    && . ~/.graphscope_env \
    && cd /home/graphscope/graphscope/interactive_engine \
    && mvn clean package -P groot -DskipTests --quiet -Drust.compile.mode="$profile" \
    && tar xzf /home/graphscope/graphscope/interactive_engine/assembly/target/groot.tar.gz -C /home/graphscope/

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -y && \
    apt-get install -y sudo default-jdk dnsutils tzdata \
        libjemalloc-dev libunwind-dev binutils less && \
    apt-get clean -y && \
    rm -rf /var/lib/apt/lists/*

ENV GRAPHSCOPE_HOME=/usr/local
ENV JAVA_HOME=/usr/lib/jvm/default-java

COPY --from=builder /home/graphscope/groot /usr/local/groot

RUN useradd -m graphscope -u 1001 \
    && echo 'graphscope ALL=(ALL) NOPASSWD:ALL' >> /etc/sudoers
RUN sudo chmod a+wrx /tmp

USER graphscope
WORKDIR /home/graphscope

ENV PATH=${PATH}:/home/graphscope/.local/bin

# init log directory
RUN sudo mkdir /var/log/graphscope \
  && sudo chown -R $(id -u):$(id -g) /var/log/graphscope
