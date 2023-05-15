# eap-operator-tx-recovery-demo


## Prerequisites
* Get access to an OpenShift instance where you have cluster administrator access. This is needed to install the EAP operator.
* Log in to the console as a cluster administrator and install the EAP operator
  * This is done in the Administrator perspective, from the OperatorHub. The name of the operator is 'JBoss EAP'. The default values should be fine.
* Make sure you have the `oc` CLI and log in as the cluster administrator
* Make sure you have the `helm` CLI
  * Add the Helm chart by following the instructions in https://jbossas.github.io/eap-charts/
* Create a new project with the `oc` CLI,: `oc new-project myproject`.


## Creating the Application Image Stream

Create the `eap7-app` application image stream by using Helm to install the `application-image-helm.yaml` Helm chart, e.g.:
```shell
helm install eap7-app -f application-image-helm.yaml jboss-eap/eap74
```
This Helm chart results in an image containing the application in the EAP runtime image, pushed to the `eap7-app` image stream.

The above will take some time to complete. Once the output of `oc get build -w` indicates the `eap7-app-<n>>` is complete, you can proceed to the next step. 

**Note:** The name `eap7-app` is important since it becomes the basis for the name of the image stream referenced later.


## Deploy the Application

To deploy the application, run:
```shell
oc apply -f application.yaml
```
This starts the application with two pods. Wait for both pods to be ready before progressing to the next step.
The names of the hosts will be `eap7-app-0` and `eap7-app-1`.

To run the application, you can access it through its OpenShift route:

```shell
curl http://$(oc get route eap7-app-route --template='{{ .spec.host }}')/services/javadetails
```
