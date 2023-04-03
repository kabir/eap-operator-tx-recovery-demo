package org.jboss.eap.operator.demos.tx.recovery.releaseserver;

import javax.enterprise.context.ApplicationScoped;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@ApplicationScoped
public class ReleasedPodsRegistry {
    private static final String POD_BASE_NAME = "eap7-app-";

    private final Set<String> releasedPods = Collections.synchronizedSet(new HashSet<>());

    public void storePodReleased(String pod) {
        String podName;
        try {
            int id = Integer.valueOf(pod);
            podName = POD_BASE_NAME + id;
        } catch (NumberFormatException e) {
            podName = pod;
        }
        System.out.println("Storing that pod is released: '" + podName + "'");
        releasedPods.add(podName);
    }

    public boolean isReleased(String pod) {
        boolean released = releasedPods.remove(pod);
        System.out.println("Checked if pod '" + pod + "' was released: " + released);
        return released;
    }
}
