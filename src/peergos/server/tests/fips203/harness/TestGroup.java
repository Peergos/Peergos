package peergos.server.tests.fips203.harness;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class TestGroup {

    @JsonProperty
    private int tgId;
    @JsonProperty
    private String testType;
    @JsonProperty
    private String parameterSet;
    @JsonProperty
    private String function;
    @JsonProperty
    private String ek;
    @JsonProperty
    private String dk;
    private List<TestCase> tests = new ArrayList<>();

    public int getTgId() {
        return tgId;
    }

    public String getParameterSet() {
        return parameterSet;
    }

    public String getFunction() {
        return function;
    }

    public String getEk() {
        return ek;
    }

    public String getDk() {
        return dk;
    }

    public List<TestCase> getTests() {
        return tests;
    }
}
