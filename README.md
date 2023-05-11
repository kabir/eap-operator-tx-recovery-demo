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

The `src/main/scripts/s2i/install.sh` script which gets run when building the server will download byteman and configure the server to use byteman in listening mode. This is important later when we 'freeze' the second commit.

## Deploy the Application

To deploy the application, run:
```shell
oc apply -f application.yaml
```
This starts the application with two pods. Wait for both pods to be ready before progressing to the next step.
The names of the hosts will be `eap7-app-0` and `eap7-app-1`.

The `src/main/scripts/s2i/initialize-server.cli` CLI script gets run on server launch and configures two XA datasources to connect to the two databases we set up.


## The Application
The application is quite simple, and made to demonstrate transaction recovery when using the EAP operator on OpenShift.

There are two XA data sources, each storing/reading data to/from different databases. When storing data, it will insert data via both XA data sources, and when reading the data it will combine the data from both XA datasources. The mentioned byteman rule triggers when doing the 2-phase commit after adding data via the application. The rule takes effect when committing the second XA resource, resulting in the actual commit being delayed for 4 minutes. This gives us time to scale down the application and demonstrate transaction recovery as we will see later. 

It exposes three REST endpoints. One allows us to add new values to the database via a POST request, another does the same but 'freezes' the transaction. The last one lets us get the values via a GET request. To make these easier to invoke, a `demo.sh` script is provided. 

### Creating an entry
```shell
./demo.sh add <value>
```
For example:
```shell
./demo.sh add hello
```
This will insert the data. The script is set up to run curl in verbose mode, so it will return the HTTP status code, headers, as well as the name of the load-balanced host that was used to store the data.

### Creating an entry and 'freezing' the transaction
```shell
./demo.sh freeze <value>
```
For example:
```shell
./demo.sh freeze hello


In the normal case this will accept the request, resulting in a '202 Accepted' status code. The request will return immediately but spawn a background thread which starts a transaction and then triggers the mentioned byteman rule to 'freeze' the transaction commit on the second XA resource. 

As all requests are load-balanced, and we need the first pod `eap7-app-0` to not be the one with an frozen, and thus inconsistent, transaction. So, if this 'freeze' command hits `eap7-app-0`, the transaction commit will not be frozen, and you will receive an HTTP Status of 409. In this case just try again until the request is accepted on another host.

### Getting all entries
```shell
./demo.sh list 
```
Before you have created any entries this will be empty. After adding entries, it will return a combined view of data we added via both XA datasources. This will be a JSON list where each entry looks something like the following:

```shell
{"hello":{"eap7-app-1":true}}
```
* `hello` - is the data we added
* `eap7-app-1` - is the name of the host that ended up serving the 'add' (or 'freeze') command
* `true` - means that both resources were properly committed. This is the 'normal', and expected state. When we look at freezing the second commit later, a value of `false` indicates the second XA resource was not committed.

## Demonstrating Transaction Recovery

Since we will be 'freezing' the transaction on `eap7-app-1`, follow its logs in a dedicated terminal window:

```shell
oc logs -f eap7-app-1
```
We will look at the logs later.

Since we need to scale down the pods reasonably quickly once we've frozen the transaction commit, prepare to do so by selecting your project in the OpenShift console's Administrator view. From there, select 'Installed Operators', then the 'JBoss EAP' operator. Then go into the 'WildFlyServer' tab, and select the `eap7-app` entry. Note the Replicas: '2 pods' entry near the bottom of the page. We will use this later to scale down the application.  


In a new terminal, execute:
```shell
./demo.sh add hello
```
This will result in output like
```shell
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
< date: Thu, 11 May 2023 13:20:14 GMT
< set-cookie: 6408e4ed2f24cda93bbdf4a4c2c125d7=7aa338a543a51ef9c0b44ddaf4ff37f2; path=/; HttpOnly
< 
* Connection #0 to host eap7-app-route-myproject.apps.cluster-hg5t5.hg5t5.sandbox478.opentlc.com left intact
eap7-app-1%
```
Thus `eap7-app-1` was used to save the entry.

Then the `list` command shows that both XA resources were successfully committed:
```shell
% ./demo.sh list              
[{"hello":{"eap7-app-1":true}}]%                                                                                        
```
As described earlier, this shows that `hello` was added via the load-balanced host `eap7-app-1`, and the `true` indicates that both XA data sources committed successfully.

Next, we try to insert a new entry and freeze the second commit of the 2-phase commit. If this load-balanced request hits `eap7-app-0`, it will return a 409 HTTP Status code. If that happens, just try again until it succeeds. 

The command to do this is
```shell
./demo.sh freeze world
```
Once it succeeds, the output should look something like the following
```shell
*   Trying 3.12.75.153:80...
* Connected to eap7-app-route-myproject.apps.cluster-77slv.77slv.sandbox2282.opentlc.com (3.12.75.153) port 80 (#0)
> POST /freeze/world HTTP/1.1
> Host: eap7-app-route-myproject.apps.cluster-77slv.77slv.sandbox2282.opentlc.com
> User-Agent: curl/7.87.0
> Accept: */*
> 
* Mark bundle as not supporting multiuse
< HTTP/1.1 202 Accepted
< content-type: application/octet-stream
< content-length: 10
< date: Thu, 11 May 2023 13:17:56 GMT
< set-cookie: 6408e4ed2f24cda93bbdf4a4c2c125d7=cb182748c3ffc95045b457bc76a0d7d1; path=/; HttpOnly
< 
* Connection #0 to host eap7-app-route-myproject.apps.cluster-77slv.77slv.sandbox2282.opentlc.com left intact
eap7-app-1%           
```
As earlier, we can see this request ended up being handled by pod `eap7-app-1`.

In the logs for `eap7-app-1` we see some output from the byteman rule
```shell
13:17:56,463 INFO  [stdout] (EE-ManagedExecutorService-default-Thread-2) Starting countdown at 1
13:17:56,465 INFO  [stdout] (EE-ManagedExecutorService-default-Thread-2) ********* topLevelCommit for java:jboss/datasources/demo-ds
13:17:56,466 INFO  [stdout] (EE-ManagedExecutorService-default-Thread-2) ***** topLevelCommit: Freezing transaction for 240 seconds java:jboss/datasources/second-ds
```
This simply explains that the commit of the second XA resource will be delayed by 4 minutes.

Looking at the output of `./demo.sh list`, we see that the second entry ('world') that we added was not successfully committed across both XA resources, just the first one.
```shell
%         
[{"hello":{"eap7-app-1":true}},{"world":{"eap7-app-1":false}}]%                                                                                                                                                                         
```

Now scale down the pod to one replica in the console.

The output from `./demo.sh list` will look the same.

In the `eap7-app-1` log, we will see the server stopping and starting again. The restart is done by the EAP operator, and is done in order to look for and recover XA transactions. A short while after this happens, you should see the output of `./demo.sh list` changes to indicate that the frozen transaction was successful (i.e. the earlier `false` is now `true`): 
```shell
[{"hello":{"eap7-app-1":true}},{"world":{"eap7-app-1":true}}]%     
``` 

This shows that the transaction recovery process has worked, and successfully committed the second XA resource.