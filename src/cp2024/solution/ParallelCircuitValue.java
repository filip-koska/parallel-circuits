package cp2024.solution;

import cp2024.circuit.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ParallelCircuitValue implements CircuitValue {

    private final AtomicBoolean value;
    private final AtomicBoolean valueIsSet;

    private final AtomicBoolean broken;

    // semaphore to halt threads waiting for the value
    private final Semaphore sem;

    public ParallelCircuitValue() {
        this.value = new AtomicBoolean();
        this.valueIsSet = new AtomicBoolean(false);
        this.sem = new Semaphore(0);
        this.broken = new AtomicBoolean(false);
    }

    // update freshly computed value
    public void setValue(Boolean value) {
        // if value was already set, throw IllegalStateException
        if (this.valueIsSet.get())
            throw new RuntimeException("Attempt to set a CircuitValue twice");
        this.value.set(value);
        this.valueIsSet.set(true);
        this.sem.release();
    }

    @Override
    public boolean getValue() throws InterruptedException {
        // sem is 0 until the value is set or the object is broken
        this.sem.acquire();
        this.sem.release();

        if (this.broken.get()) // object was broken during value computation
            throw new InterruptedException();
        return this.value.get();
    }

    public void breakCircuitValue() {
        this.broken.set(true);
        this.sem.release();
    }
}


