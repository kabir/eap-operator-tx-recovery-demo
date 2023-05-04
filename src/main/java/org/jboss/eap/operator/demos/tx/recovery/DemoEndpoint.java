package org.jboss.eap.operator.demos.tx.recovery;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("/")
public class DemoEndpoint {

    @Inject
    DemoBean demoBean;

    @POST
    @Path("{value}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response send(@PathParam("value") String value) {
        System.out.println("Received value: " + value);
        return demoBean.addEntryToRunInTransactionInBackground(value, false);
    }

    @POST
    @Path("freeze/{value}")
    @Consumes(MediaType.TEXT_PLAIN)
    public Response sendAndFreezeTx(@PathParam("value") String value) {
        System.out.println("Received value for frozen transaction: " + value);
        return demoBean.addEntryToRunInTransactionInBackground(value, true);
    }

    @GET
    @Produces(APPLICATION_JSON)
    public List<Map<String, Map<String, Boolean>>> getAllValues() {
        return demoBean.getAllValues();
    }


}
