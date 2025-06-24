package peergos.server.tests.fips203.harness;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class TestPrompt {

    @JsonProperty
    private int vsId;
    @JsonProperty
    private String algorithm;
    @JsonProperty
    private String mode;
    @JsonProperty
    private String revision;

    @JsonProperty("isSample")
    private boolean sample;

    private List<TestGroup> testGroups = new ArrayList<>();

    public List<TestGroup> getTestGroups() {
        return testGroups;
    }
}
