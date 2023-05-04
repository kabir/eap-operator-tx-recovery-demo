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


# This has the effect of replacing
# if [ "x$JBOSS_MODULES_SYSTEM_PKGS" = "x" ]; then
#   JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman"
# fi
#
# with
#
# if [ "x$JBOSS_MODULES_SYSTEM_PKGS" = "x" ]; then
#   JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman"
# else
#   JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman,$JBOSS_MODULES_SYSTEM_PKGS"
# fi
#
# '\s*' is ignore whitespace.
# I would have liked to match the full input string rather than the single 'JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman"'
# line but cannot figure out how to do that :-)

sed -i 's/^\s*JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman"\s*$/   JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman"\nelse\n   JBOSS_MODULES_SYSTEM_PKGS="org.jboss.byteman,$JBOSS_MODULES_SYSTEM_PKGS"/g' $JBOSS_HOME/bin/standalone.conf

echo "export BYTEMAN_HOME=\"$JBOSS_HOME/extensions/byteman\""  >> $JBOSS_HOME/bin/standalone.conf
# -Xverify:none is deprecated from Java 13, but I don't see another way right now
# to follow the recommendations in https://www.baeldung.com/java-lang-verifyerror
echo "JAVA_OPTS=\"-Xverify:none -javaagent:\${BYTEMAN_HOME}/lib/byteman.jar=boot:\${BYTEMAN_HOME}/lib/byteman.jar,listener:true \${JAVA_OPTS}\"" >> $JBOSS_HOME/bin/standalone.conf


#echo "Double checking standalone.conf"
#tail $JBOSS_HOME/bin/standalone.conf
#echo "Double-checked standalone.conf file."

