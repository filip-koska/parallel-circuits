package cp2024.solution;

// utility class to hold a pair of int and boolean
public class CommPair {

    private final int id;
    private final boolean val;

    public CommPair(int id, boolean val) {
        this.id = id;
        this.val = val;
    }

    public int getId() {
        return this.id;
    }

    public boolean getVal() {
        return this.val;
    }

}
