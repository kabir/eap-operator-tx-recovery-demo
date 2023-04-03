#!/bin/sh


if [ "$1" == "add" ]; then
  curl -X POST -v http://$(oc get route eap7-app-route --template='{{ .spec.host }}')/$2
elif [ "$1" == "list" ]; then
  curl http://$(oc get route eap7-app-route --template='{{ .spec.host }}')
elif [ "$1" == "release" ]; then
  curl -X POST http://$(oc get route eap7-app-release-server --template='{{ .spec.host }}')/$2
else
  echo "Unknown command $1"
fi

