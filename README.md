# eap-operator-tx-recovery-demo


## Prerequisites
* Get access to an OpenShift instance where you have cluster administrator access. This is needed to install the EAP operator.
* Log in to the console as a cluster administrator and install the EAP operator
  * This is done in the Administrator perspective, from the OperatorHub. The name of the operator is 'JBoss EAP'. The default values should be fine.
* Make sure you have the `oc` CLI and log in as the cluster administrator
* Make sure you have the `helm` CLI
* Create a new project with the `oc` CLI,: `oc new-project myproject`.
* Create the needed postgres database with:
```shell
oc new-app --name postgresql \
     --env POSTGRESQL_USER=admin \
     --env POSTGRESQL_PASSWORD=admin \
     --env POSTGRESQL_DATABASE=sampledb \
     postgresql:latest
```

## Creating the Image Stream

Create the `eap7-app` application image stream by using Helm to install the `application-image-helm.yaml` Helm chart, e.g.:
```shell
 helm install eap7-app -f application-image-helm.yaml jboss-eap/eap74
```
This Helm chart results in an image containing the application in the EAP runtime image, pushed to the `eap7-app` image stream.

The above will take some time to complete. Once the output of `oc get build -w` indicates the `eap7-app-<n>>` is complete, you can proceed to the next step.

**Note:** The name `eap7-app` is important since it becomes the basis for the name of the image stream referenced from application.yaml, the names of the pods and other things. 

## Deploy the Application

To deploy the application, run:
```shell
oc apply -f application.yaml
```
This starts the application with one pod. Wait for the pod to be ready before progressing to the next step.


## The Application
The application is quite simple, and made to demonstrate transaction recovery when using the EAP operator on OpenShift.

It exposes the following two REST endpoints with examples how to invoke them:

### Getting all entries
```shell
curl http://$(oc get route eap7-app-route --template='{{ .spec.host }}')
```
Before you have created any entries this will be empty.

### Creating an entry in a long-running transaction
```shell
curl -X POST -v http://$(oc get route eap7-app-route --template='{{ .spec.host }}')/<value>
```
For example:
```shell
curl -X POST -v http://$(oc get route eap7-app-route --template='{{ .spec.host }}')/hello
```

In the normal case this will accept the request, resulting in a '202 Accepted' status code. The request will return immediately but spawn a background thread which starts a transaction and then waits for a latch to be released. We will look at how to release the latch in a second. Once the latch is released, it will store an entity in the database. If the transaction is not released and times out, the latch is also released.

Once the latch is released, you may call this message again. 

If called before the latch is released, you will receive a '409 Conflict' status code.

Since the name of the pod we are locking the transaction on is important for later steps, the name of the pos is included in the result of this call. For example `eap7-app-0` indicates that the pod `eap7-app-0` is the one we have to go to to release the latch.

### Releasing the latch
You can either go to the pod in the openshift console, and go to the terminal view there. Or you can run `oc rsh <pod name>`. In both cases you should be taken to the `/home/jboss` folder which is being watched by the application.

Once in the `/home/jboss` folder, execute:

```shell
touch release
```
This will create a marker file called `release`, which will be picked up by the application. This in turn releases the latch, and completes the transaction.

Play a bit with the above commands to get a feel for the application. 

## Recovery Scenarios
Now we get to the main point which is to demon


