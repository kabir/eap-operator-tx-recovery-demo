#!/bin/sh

set -x
echo "Running install.sh"
injected_dir=$1
echo "Copying "$1" directory to $JBOSS_HOME/extensions"
cp -rf ${injected_dir} $JBOSS_HOME/extensions