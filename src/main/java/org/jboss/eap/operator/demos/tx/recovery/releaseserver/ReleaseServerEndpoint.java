package org.jboss.eap.operator.demos.tx.recovery.releaseserver;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Since we can't rsh into a terminating pod, we host this simple server to store the release markers. The main
 * server demonstrating the transaction recovery will poll the GET endpoint.
 * <p>
 * Users will call the POST endpoint to do the release.
 * <p>
 * Since I am lazy, I am keeping this in the same code base. A separate OpenShift deployment is used to host the application.
 */
@Path("/release")
public class ReleaseServerEndpoint {
    private static final String POD_BASE_NAME = "eap7-app-";

    Set<String> releasedPods = Collections.synchronizedSet(new HashSet<>());

    @POST
    @Path("{podName}")
    @Consumes(MediaType.TEXT_PLAIN)
    public void send(@PathParam("podName") int podNumber) {
        String podName = POD_BASE_NAME + podNumber;
        System.out.println("Storing that pod is released: " + podName);
        releasedPods.add(podName);
    }

    @GET
    @Path("{podName}")
    @Produces(MediaType.TEXT_PLAIN)
    public int isReleased(@PathParam("podName") String podName) {
        boolean released = releasedPods.remove(podName);
        System.out.println("Checked if " + podName + " was released: " + released);
        return released ? 1 : 0;
    }
}
