ARG from_image
FROM $from_image

ENV BYTEMAN_HOME=$HOME/byteman-download-4.0.21/
COPY xa.btm $HOME/xa.btm
RUN curl https://downloads.jboss.org/byteman/4.0.21/byteman-download-4.0.21-bin.zip -o byteman.zip && \
    unzip byteman.zip && \
    chmod -R o-rwx byteman-download-4.0.21/ && \
    echo "JAVA_OPTS=\"-javaagent:\${BYTEMAN_HOME}/lib/byteman.jar=boot:\${BYTEMAN_HOME}/lib/byteman.jar,listener:true \${JAVA_OPTS}\"" >> /opt/eap/bin/standalone.conf


