# eap-operator-tx-recovery-demo


Get access to an OpenShift instance where you have cluster administrator access. This is needed to install the EAP operator.

Log in as a cluster administrator, and install the EAP operator. This is done in the Adminstrator perspective, from the OperatorHub. The name of the operator is 'JBoss EAP'. The default values should be fine.

Log in with the `oc` CLI, and create a project. e.g. `oc new-project myproject`.

Create the application image stream by using Helm to install the `application-image-helm.yaml` Helm chart, e.g.:
```shell
helm install eap7-app -f charts/application-image-helm.yaml jboss-eap/eap74
```

