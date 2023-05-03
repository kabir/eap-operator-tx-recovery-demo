#!/usr/bin/env bash

# This script (postconfigure.sh) is executed during
# launch of the application server (not during the build)
# This script is expected to be copied to
# $JBOSS_HOME/extensions/ folder by script install.sh


echo "Configuring server ${JBOSS_HOME}/extensions/initialize-server.cli"

POSTCONFIGURE_PROPERTIES_FILE="${JBOSS_HOME}/extensions/cli.openshift.properties"

[ "x$SCRIPT_DEBUG" = "xtrue" ] && cat "${JBOSS_HOME}/extensions/initialize-server.cli"


"${JBOSS_HOME}"/bin/jboss-cli.sh --file="${JBOSS_HOME}/extensions/initialize-server.cli" --properties="${POSTCONFIGURE_PROPERTIES_FILE}"
