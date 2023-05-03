package org.jboss.eap.operator.demos.tx.recovery;

import javax.annotation.Resource;
import javax.enterprise.concurrent.ManagedExecutorService;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.transaction.Synchronization;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.Transactional;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class DemoBean {

    @PersistenceContext(unitName = "demo")
    EntityManager demoEm;

    @PersistenceContext(unitName = "second")
    EntityManager secondEm;

    AtomicBoolean handlingRequest = new AtomicBoolean(false);

    @Inject
    DemoBean internalDelegate;

    // It seems the max number of active tasks here is 2
    @Resource
    private ManagedExecutorService executor;

    @Resource
    TransactionSynchronizationRegistry txSyncRegistry;

    private final String hostName;

    public DemoBean() {
        String hostName = System.getenv().get("HOSTNAME");
        if (hostName == null) {
            hostName = "local install";
        }
        this.hostName = hostName;
    }

    Response addEntryToRunInTransactionInBackground(String value, boolean crash) {
        if (value == null) {
            System.err.println("Null value.");
            return Response.status(Response.Status.BAD_REQUEST.getStatusCode()).build();
        }

        if (handlingRequest.get()) {
            System.err.println("There is currently a transaction in process on this node");
            return Response.status(Response.Status.CONFLICT.getStatusCode())
                    .entity(hostName + " appears to be stuck in an XA transaction")
                    .build();
        }

        if (crash && hostName.endsWith("-0")) {
            System.err.println("Ignoring request to crash first pod " + hostName);
            return Response.status(Response.Status.CONFLICT.getStatusCode())
                    .entity(hostName + " appears to be stuck in an XA transaction")
                    .build();
        }

        handlingRequest.set(true);

        if (crash) {
            installBytemanRules();
        }


        System.out.println("Submitting background task to store the entity in a long transaction");
        executor.submit(new Runnable() {
            @Override
            public void run() {
                System.out.println("Calling Transactional method from background task");
                internalDelegate.addEntriesInXaTx(value);
            }
        });
        return Response.accepted().entity(hostName).build();
    }

    private void installBytemanRules() {
        Path homeDir = Paths.get(System.getProperty("jboss.home.dir"));
        Path extensionsDir = homeDir.resolve("extensions");
        Path bmSubmit = extensionsDir.resolve("byteman/bin/bmsubmit.sh");
        Path rules = extensionsDir.resolve("xa.btm");

        List<String> commands = Arrays.asList(
                bmSubmit.normalize().toAbsolutePath().toString(),
                rules.normalize().toAbsolutePath().toString());

        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.directory(homeDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        try {
            int exit = process.waitFor();
            System.out.println("Exit code: " + exit);
            if (exit != 0) {
                throw new RuntimeException("Was not able to add rules." + exit);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Called internally by the transactionExecutor Runnable
    @Transactional
    void addEntriesInXaTx(String value) {

        System.out.println("Transaction started. Registering Tx Synchronization");
        txSyncRegistry.registerInterposedSynchronization(new Callback());

        System.out.println("Persisting entity with value in demoDs: " + value);
        DemoEntity entity = new DemoEntity();
        entity.setValue(value);
        entity.setHost(hostName);
        demoEm.persist(entity);

        Long demoEntityId = entity.getId();
        SecondEntity secondEntity = new SecondEntity();
        secondEntity.setDemoEntityId(demoEntityId);
        secondEm.persist(secondEntity);
    }

    @Transactional
    public List<Map<String, Map<String, Boolean>>> getAllValues() {
        TypedQuery<SecondEntity> secondQuery = secondEm.createQuery("SELECT s from SecondEntity s", SecondEntity.class);
        Set<Long> demoIds = secondQuery.getResultList().stream().map(v -> v.getDemoEntityId()).collect(Collectors.toSet());

        TypedQuery<DemoEntity> demoQuery = demoEm.createQuery("SELECT d from DemoEntity d", DemoEntity.class);
        List<Map<String, Map<String, Boolean>>> values =
                demoQuery.getResultList().stream()
                        .map(v -> Collections.singletonMap(v.getValue(), map(v.getHost(), demoIds.contains(v.getId()))))
                        .collect(Collectors.toList());


        return values;
    }

    private void freeLatch() {
        handlingRequest.set(false);
    }

    private Map<String, Boolean> map(String key, Boolean value) {
        Map<String, Boolean> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    private class Callback implements Synchronization {

        @Override
        public void beforeCompletion() {

        }

        @Override
        public void afterCompletion(int status) {
            System.out.println("Tx completed. Opening up for new requests. Status: " + status);
            freeLatch();
        }
    }
}
