package org.jboss.eap.operator.demos.tx.recovery.releaseserver;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

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



    @Inject
    ReleasedPodsRegistry releasedPodsRegistry;

    @POST
    @Path("{podName}")
    @Consumes(MediaType.TEXT_PLAIN)
    public void send(@PathParam("podName") String pod) {
        releasedPodsRegistry.storePodReleased(pod);
    }

    @GET
    @Path("{podName}")
    @Produces(MediaType.TEXT_PLAIN)
    public int isReleased(@PathParam("podName") String podName) {
        boolean released = releasedPodsRegistry.isReleased(podName);
        return released ? 1 : 0;
    }
}
