package peergos.shared.crypto;

public class RequiredDifficulty {
    public final int requiredDifficulty;

    public RequiredDifficulty(int requiredDifficulty) {
        this.requiredDifficulty = requiredDifficulty;
    }

    @Override
    public String toString() {
        return "Difficulty: " + requiredDifficulty;
    }
}
