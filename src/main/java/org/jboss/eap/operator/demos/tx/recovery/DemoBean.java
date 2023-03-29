package org.jboss.eap.operator.demos.tx.recovery;

import javax.enterprise.context.ApplicationScoped;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;
import javax.transaction.Transactional;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

@ApplicationScoped
public class DemoBean {

    @PersistenceContext(unitName = "demo")
    EntityManager em;

    volatile CountDownLatch hangTxLatch;

    @Transactional
    Response addEntryAndWait(String value) {
        synchronized (this) {
            if (hangTxLatch != null) {
                throw new IllegalStateException("We are already saving an entry and waiting");
            } else {
                System.out.println("Creating latch");
                hangTxLatch = new CountDownLatch(1);
            }
        }
        try {
            System.out.println("Waiting for the latch to be released....");
            hangTxLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Thread was interrupted");
            Response.status(Response.Status.REQUEST_TIMEOUT).build();
        }

        System.out.println("Persisting entity with value: " + value);
        DemoEntity entity = new DemoEntity();
        entity.setValue(value);
        em.persist(entity);

        System.out.println("Persisted");
        return Response.ok().build();
    }

    public void release() {
        synchronized (this) {
            if (hangTxLatch != null) {
                System.out.println("Releasing latch");
                hangTxLatch.countDown();
                hangTxLatch = null;
            }
        }
    }

    @Transactional
    public List<String> getAllValues() {
        TypedQuery<DemoEntity> query = em.createQuery("SELECT d from DemoEntity d", DemoEntity.class);
        List<String> values = query.getResultList().stream().map(v -> v.getValue()).collect(Collectors.toList());
        return values;
    }
}
