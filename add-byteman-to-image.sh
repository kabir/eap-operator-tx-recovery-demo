#!/bin/sh

# Before running this script you must have
#   - Logged in with `oc login`
#   - Selected or created a project with `oc project` or `oc new-project`

export FROM_IMAGE_NAME=eap7-app:latest
export BYTEMAN_IMAGE_NAME=eap7-app-byteman:latest
export OPENSHIFT_NS=$(oc project -q)
oc registry login
# Copy the route in the env variable OPENSHIFT_IMAGE_REGISTRY
OPENSHIFT_IMAGE_REGISTRY=$(oc registry info)
docker login -u openshift -p $(oc whoami -t)  "${OPENSHIFT_IMAGE_REGISTRY}"

FROM_IMAGE="${OPENSHIFT_IMAGE_REGISTRY}/${OPENSHIFT_NS}/${FROM_IMAGE_NAME}"
BYTEMAN_IMAGE="${OPENSHIFT_IMAGE_REGISTRY}/${OPENSHIFT_NS}/${BYTEMAN_IMAGE_NAME}"

docker build . --build-arg from_image="${FROM_IMAGE}" -t "${BYTEMAN_IMAGE}"

docker tag  $IMAGE $OPENSHIFT_IMAGE_REGISTRY/$OPENSHIFT_NS/$BYTEMAN_IMAGE
docker push  "${BYTEMAN_IMAGE}"

