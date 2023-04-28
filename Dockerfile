ARG from_image
FROM $from_image

ENV BYTEMAN_HOME=$HOME/byteman-download-4.0.21/
COPY xa.btm $HOME/xa.btm
RUN curl https://downloads.jboss.org/byteman/4.0.21/byteman-download-4.0.21-bin.zip -o byteman.zip && \
    unzip byteman.zip && \
    chmod -R o-rwx byteman-download-4.0.21/ && \
    # -Xverify:none is deprecated from Java 13, but I don't see another way right now
    # to follow the recommendations in https://www.baeldung.com/java-lang-verifyerror
    echo "JAVA_OPTS=\"-Xverify:none -javaagent:\${BYTEMAN_HOME}/lib/byteman.jar=boot:\${BYTEMAN_HOME}/lib/byteman.jar,listener:true \${JAVA_OPTS}\"" >> /opt/eap/bin/standalone.conf


