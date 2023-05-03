#!/bin/sh

set -x
echo "Running install.sh"
injected_dir=$1
echo "Copying "$1" directory to $JBOSS_HOME/extensions"
cp -rf ${injected_dir} $JBOSS_HOME/extensions

# Also download byteman and add it to the extensions folder
echo "Adding and configuring byteman..."
curl https://downloads.jboss.org/byteman/4.0.21/byteman-download-4.0.21-bin.zip -o byteman.zip
unzip byteman.zip
mv byteman-download-4.0.21 $JBOSS_HOME/extensions/byteman
chmod -R o-rwx $JBOSS_HOME/extensions/byteman

echo "Checking if there is a standalone.conf file."
cat $JBOSS_HOME/bin/standalone.conf
echo "Checked for standalone.conf file."


echo "export BYTEMAN_HOME=\"$JBOSS_HOME/extensions/byteman\""  >> $JBOSS_HOME/bin/standalone.conf
# -Xverify:none is deprecated from Java 13, but I don't see another way right now
# to follow the recommendations in https://www.baeldung.com/java-lang-verifyerror
echo "JAVA_OPTS=\"-Xverify:none -javaagent:\${BYTEMAN_HOME}/lib/byteman.jar=boot:\${BYTEMAN_HOME}/lib/byteman.jar,listener:true \${JAVA_OPTS}\"" >> $JBOSS_HOME/bin/standalone.conf


echo "Double checking standalone.conf"
tail $JBOSS_HOME/bin/standalone.conf
echo "Double-checked standalone.conf file."

