package io.github.jaronie.centroidServer.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The only three states a job can be in, per the API spec. Note there is no
 * PENDING: a job is processing from the moment it is created.
 * 
 * Enum instead of bare strings ensures compiler catches a typo before it reaches a client.
 */
public enum JobStatus {

    PROCESSING("processing"),
    DONE("done"),
    ERROR("error");

    private final String json;

    JobStatus(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}
