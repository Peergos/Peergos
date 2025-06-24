package peergos.server.tests.fips203.harness;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.HashMap;
import java.util.Map;

public class TestCase {

    private int tcId;

    private Map<String,Object> values = new HashMap<>();

    @JsonAnySetter
    public void setValues(String name, Object value) {
        values.put(name, value);
    }

    public int getTcId() {
        return tcId;
    }

    public Map<String, Object> getValues() {
        return values;
    }
}
