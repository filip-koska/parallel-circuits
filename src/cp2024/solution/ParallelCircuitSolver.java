package cp2024.solution;

import cp2024.circuit.*;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

public class ParallelCircuitSolver implements CircuitSolver {

    // acceptsRequests == true <=> stop() wasn't yet called on the solver
    private final AtomicBoolean acceptsRequests;

    // array of threads corresponding to roots of provided circuits
    private final List<Thread> rootThreads;

    // mechanism for synchronising solve() and stop()
    private final ReentrantLock sem;

    public ParallelCircuitSolver() {
        this.acceptsRequests = new AtomicBoolean(true);
        this.rootThreads = new ArrayList<>();
        this.sem = new ReentrantLock();
    }

    private static class NodeHelper implements Runnable {

        // root threads will have id equal to 0, children threads greater than 0
        protected final Integer id;
        protected final CircuitNode node;
        protected AtomicBoolean myValue;
        protected final List<Thread> threadList;

        // queue whence the parent takes values of args
        protected BlockingQueue<CommPair> parentQueue;

        public NodeHelper(int id, CircuitNode cn) {
            this.id = id;
            this.myValue = new AtomicBoolean();
            this.node = cn;
            this.threadList = new ArrayList<>();
        }

        public NodeHelper(int id, CircuitNode cn, BlockingQueue<CommPair> parentQueue) {
            this(id, cn);
            this.parentQueue = parentQueue;
        }

        protected void computeNodeValue() {
            try {
                if (this.node.getType() == NodeType.LEAF)
                    this.myValue.set(((LeafNode) this.node).getValue());
                else {
                    CircuitNode[] args = this.node.getArgs();
                    // queue children threads will use to pass their nodes' values
                    BlockingQueue<CommPair> q = new ArrayBlockingQueue<>(args.length);
                    for (int i = 0; i < args.length; ++i) {
                        this.threadList.add(new Thread(new NodeHelper(i + 1, args[i], q)));
                        this.threadList.get(i).start();
                    }
                    this.myValue.set(solveNode(this.node, this.threadList, q));

                }
                // if the thread is not a root thread, add result to parent's queue
                if (this.id > 0)
                    this.parentQueue.add(new CommPair(this.id, this.myValue.get()));
            } catch (InterruptedException e) {
                this.handleInterrupt();
            }
        }

        @Override
        public void run() {
            this.computeNodeValue();
        }

        // interrupt children and end execution (will always be called at the end of run())
        protected void handleInterrupt() {
            Thread.currentThread().interrupt();
            try {
                interruptChildren(this.threadList, true);
            } catch (InterruptedException e) {}
        }
    }

    // NodeHelper override designed to start computations of a circuit
    private static class RootNodeHelper extends NodeHelper {

        // there is a 1-1 relationship between CircuitValues and root threads
        private final ParallelCircuitValue cv;

        public RootNodeHelper(int id, CircuitNode cn, ParallelCircuitValue cv) {
            super(id, cn);
            this.cv = cv;
        }

        @Override
        public void run() {
            this.computeNodeValue();
            // if computation ended, set value of correspondent CircuitValue
            this.cv.setValue(this.myValue.get());
        }

        @Override
        protected void handleInterrupt() {
            Thread.currentThread().interrupt();
            // set the correspondent CircuitValue's state to broken
            this.cv.breakCircuitValue();
            try {
                interruptChildren(this.threadList, true);
            } catch (InterruptedException e) {}
        }
    }

    private static boolean solveNode(CircuitNode node, List<Thread> threadList, BlockingQueue<CommPair> q) throws InterruptedException {
        return switch(node.getType()) {
            case IF -> solveIF(threadList, q);
            case AND -> solveAND(threadList, q);
            case OR -> solveOR(threadList, q);
            case NOT -> solveNOT(threadList, q);
            case GT -> solveGT(threadList, q, ((ThresholdNode) node).getThreshold());
            case LT -> solveLT(threadList, q, ((ThresholdNode) node).getThreshold());
            default -> throw new RuntimeException("Illegal type " + node.getType());
        };
    }

    private static boolean solveIF(List<Thread> threadList, BlockingQueue<CommPair> q) throws InterruptedException {
        boolean[] childResults = new boolean[threadList.size()];
        boolean[] known = new boolean[threadList.size()];
        try {
            CommPair cp;
            for (int i = 0; i < threadList.size(); ++i) {
                cp = q.take();
                childResults[cp.getId() - 1] = cp.getVal();
                known[cp.getId() - 1] = true;
                // condition value is known; we know which branch can be discarded
                if (known[0]) {
                    int resChild = childResults[0] ? 1 : 2;
                    threadList.get(resChild).join();
                    interruptChildren(threadList, false);
                    while (!known[resChild]) {
                        cp = q.take();
                        childResults[cp.getId() - 1] = cp.getVal();
                        known[cp.getId() - 1] = true;
                    }
                    interruptChildren(threadList, false);
                    return childResults[resChild];
                } else if (known[1] && known[2] && childResults[1] == childResults[2]) {
                    // both branches have the same value
                    interruptChildren(threadList, false);
                    return childResults[1];
                }

            }
            return false; // placeholder

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptChildren(threadList, true);
            return false;
        }
    }

    private static boolean solveAND(List<Thread> threadList, BlockingQueue<CommPair> q) throws InterruptedException {
        try {
            for (int i = 0; i < threadList.size(); ++i) {
                CommPair qp = q.take();
                // at least one argument evaluates to false; terminate other threads
                if (!qp.getVal()) {
                    interruptChildren(threadList, false);
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptChildren(threadList, true);
            return false; // placeholder
        }
    }

    private static boolean solveOR(List<Thread> threadList, BlockingQueue<CommPair> q) throws InterruptedException {
        try {
            for (int i = 0; i < threadList.size(); ++i) {
                CommPair qp = q.take();
                // at least one argument evaluates to true; terminate other threads
                if (qp.getVal()) {
                    interruptChildren(threadList, false);
                    return true;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptChildren(threadList, true);
            return false;
        }
    }

    private static boolean solveNOT(List<Thread> threadList, BlockingQueue<CommPair> q) throws InterruptedException {
        try {
            return !q.take().getVal();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptChildren(threadList, true);
            return false;
        }
    }

    private static boolean solveGT(List<Thread> threadList, BlockingQueue<CommPair> q, int threshold) throws InterruptedException {
        // threshold is unattainable
        if (threshold >= threadList.size()) {
            interruptChildren(threadList, false);
            return false;
        }

        int trueCnt = 0;
        try {
            for (int i = 0; i < threadList.size(); ++i) {
                boolean childRes = q.take().getVal();
                if (childRes)
                    ++trueCnt;
                // threshold is already attained or is unattainable
                if (trueCnt > threshold || trueCnt + (threadList.size() - i - 1) <= threshold) {
                    interruptChildren(threadList, false);
                    return trueCnt > threshold;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptChildren(threadList, true);
            return false;
        }
    }

    private static boolean solveLT(List<Thread> threadList, BlockingQueue<CommPair> q, int threshold) throws InterruptedException {
        // threshold is always attained
        if (threshold > threadList.size()) {
            interruptChildren(threadList, false);
            return true;
        }

        int trueCnt = 0;
        try {
            for (int i = 0; i < threadList.size(); ++i) {
                boolean childRes = q.take().getVal();
                if (childRes)
                    ++trueCnt;
                // threshold is already attained or is unattainable
                if (trueCnt >= threshold || trueCnt + (threadList.size() - i - 1) < threshold) {
                    interruptChildren(threadList, false);
                    return trueCnt < threshold;
                }
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interruptChildren(threadList, true);
            return false;
        }
    }

    private static void interruptChildren(List<Thread> threadList, boolean throwException) throws InterruptedException {
        int i = 0;
        for (; i < threadList.size(); ++i)
            threadList.get(i).interrupt();
        i = 0;
        while (i < threadList.size()) {
            try {
                threadList.get(i).join();
                ++i;
            } catch (InterruptedException e) {}
        }
        if (throwException)
            throw new InterruptedException();
    }

    @Override
    public CircuitValue solve(Circuit c) {
        this.sem.lock();
        // if solver is "broken" (solve had been called), return broken value
        if (!this.acceptsRequests.get()) {
            this.sem.unlock();
            return new ParallelBrokenCircuitValue();
        }

        ParallelCircuitValue cv = new ParallelCircuitValue();
        // add new root thread to handle value computation
        this.rootThreads.add(new Thread(new RootNodeHelper(0, c.getRoot(), cv)));
        this.rootThreads.get(this.rootThreads.size() - 1).start();
        this.sem.unlock();
        return cv;

    }

    @Override
    public void stop() {
        this.sem.lock();
        this.acceptsRequests.set(false);
        try {
            interruptChildren(this.rootThreads, true);
        } catch (InterruptedException e) {}
        this.sem.unlock();
    }
}