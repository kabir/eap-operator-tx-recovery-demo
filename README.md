# eap-operator-tx-recovery-demo


## Prerequisites
* Get access to an OpenShift instance where you have cluster administrator access. This is needed to install the EAP operator.
* Log in to the console as a cluster administrator and install the EAP operator
  * This is done in the Administrator perspective, from the OperatorHub. The name of the operator is 'JBoss EAP'. The default values should be fine.
* Make sure you have the `oc` CLI and log in as the cluster administrator
* Make sure you have the `helm` CLI
  * Add the Helm chart by following the instructions in https://jbossas.github.io/eap-charts/
* Create a new project with the `oc` CLI,: `oc new-project myproject`.
* Create the needed postgres databases with:
```shell
oc new-app --name postgresql-demo \
     --env POSTGRESQL_USER=admin \
     --env POSTGRESQL_PASSWORD=admin \
     --env POSTGRESQL_DATABASE=sampledb \
     --env POSTGRESQL_MAX_CONNECTIONS=100 \
     --env POSTGRESQL_MAX_PREPARED_TRANSACTIONS=100\
   postgresql:latest

oc new-app --name postgresql-second \
     --env POSTGRESQL_USER=admin \
     --env POSTGRESQL_PASSWORD=admin \
     --env POSTGRESQL_DATABASE=sampledb \
     --env POSTGRESQL_MAX_CONNECTIONS=100 \
     --env POSTGRESQL_MAX_PREPARED_TRANSACTIONS=100\
   postgresql:latest
```

## Creating the Application Image Stream

Create the `eap7-app` application image stream by using Helm to install the `application-image-helm.yaml` Helm chart, e.g.:
```shell
helm install eap7-app -f application-image-helm.yaml jboss-eap/eap74
```
This Helm chart results in an image containing the application in the EAP runtime image, pushed to the `eap7-app` image stream.

The above will take some time to complete. Once the output of `oc get build -w` indicates the `eap7-app-<n>>` is complete, you can proceed to the next step.

**Note:** The name `eap7-app` is important since it becomes the basis for the name of the image stream referenced later.

## Adding Byteman to the image

We will be using byteman to inject some rules to make the transaction 'freeze' during the 2-phase commit.

Run:
```shell
./add-byteman-to-image.sh 
```
This will pull the runtime image containing our application, that we created in the last step. It then modifies the image as follows:

* Downloads Byteman
* Modifies the server startup script so that byteman can have rules added

The image is then built and pushed to a new image strean called `eap7-app-byteman`. We will be using this image stream when deploying our application.

## Deploy the Application

To deploy the application, run:
```shell
oc apply -f application.yaml
```
This starts the application with one pod. Wait for the pod to be ready before progressing to the next step.


## The Application
The application is quite simple, and made to demonstrate transaction recovery when using the EAP operator on OpenShift.

It exposes two REST endpoints. One allows us to add new values to the database via a POST request, and the other lets us get the values via a GET request. To make these easier to invoke, the `demo.sh` script is provided. 

### Getting all entries
```shell
./demo.sh list 
```
Before you have created any entries this will be empty.

### Creating an entry in a long-running transaction
```shell
./demo.sh add <value>
```
For example:
```shell
./demo.sh add hello
```

In the normal case this will accept the request, resulting in a '202 Accepted' status code. The request will return immediately but spawn a background thread which starts a transaction and then waits for a latch to be released. We will look at how to release the latch in a second. Once the latch is released, it will store an entity in the database. If the transaction is not released and times out, the latch is also released.

Once the latch is released, you may call this endpoint again. If called before the latch is released, you will receive a '409 Conflict' status code.

Since the name of the pod we are locking the transaction on is important for later steps, the name of the pos is included in the result of this call. For example `eap7-app-0` indicates that the pod `eap7-app-0` is the one we have to go to in order to release the latch.

### Releasing the latch

The release server mentioned earlier is used to release the transaction on a pod. 

It is done as follows:
```shell
./demo.sh release <pod name, or numeric prefix>
```

Since we need to release the latch on the server which is blocked, we need to specify the pod we should work on.

We can either use the full pod name as returned by the `./demo.sh add` command:
```shell
./demo.sh release eap7-app-0
```
or, we can simply use the numeric suffix:
```shell
./demo.sh release 0
```

## Recovery Scenarios
Now we get to the main point which is to demonstrate that the server will remain up until the transaction is released or times out.

All examples expect the following initial state:

* The database is up and running
* The release server is running
* The application is running with one pod

## Running transaction blocks shutdown (single node)
In this example we will have just one application pod. We will start a long running transaction, and then try to scale down the application to zero pods with the operator. The pod should remain running until we have released the transaction.

In a terminal window, run `oc get pods -w`. The output should looks something like:
```shell
% oc get pods -w                                
NAME                                       READY   STATUS    RESTARTS   AGE
eap7-app-0                                 1/1     Running   0          22m
eap7-app-release-server-7f9dd7fcf8-p79g5   1/1     Running   0          33m
postgresql-644b8c898f-t4k64                1/1     Running   0          3h55m
```

The important pod here is the `eap7-app-0` one.

In another terminal, start a long-running transaction by running `./demo.sh add hello1`:
```shell
% ./demo.sh add hello1                                                
*   Trying 18.189.230.211:80...
* Connected to eap7-app-route-myproject.apps.cluster-hg5t5.hg5t5.sandbox478.opentlc.com (18.189.230.211) port 80 (#0)
> POST /hello1 HTTP/1.1
> Host: eap7-app-route-myproject.apps.cluster-hg5t5.hg5t5.sandbox478.opentlc.com
> User-Agent: curl/7.86.0
> Accept: */*
> 
* Mark bundle as not supporting multiuse
< HTTP/1.1 202 Accepted
< content-type: application/octet-stream
< content-length: 10
< date: Mon, 03 Apr 2023 13:20:14 GMT
< set-cookie: 6408e4ed2f24cda93bbdf4a4c2c125d7=7aa338a543a51ef9c0b44ddaf4ff37f2; path=/; HttpOnly
< 
* Connection #0 to host eap7-app-route-myproject.apps.cluster-hg5t5.hg5t5.sandbox478.opentlc.com left intact
eap7-app-0%
```
We see that this got accepted, and that this is running on the pod `eap7-app-0`. As mentioned earlier, this persisting of the entry happens in a background thread on the server and the transaction will not end until it times out (the timeout is 5 minutes) or it is released.

In the OpenShift console, go to 'Installed Operators/JBoss EAP' Then on the 'WildFlyServer' tab, go to `eap7-app' and reduce the number of pods to zero. 

In terminal containing the output from `oc get pods -w`, note that the `eap7-app-0` pods remains at `READY=1/1`.

In the other terminal run `./demo.sh release eap7-app-0` to release the transaction. 

In the terminal containing the output from `oc get pods -w`, you should now see that the `eap7-app-0` pod is allowed to scale down.

Before moving on to the next example, make sure the pod is up and running again


<!-- 
## Running transaction blocks shutdown and is freed when Tx times out 

  As the above example isn't working the way I expected, am putting this one on hold 
-->

