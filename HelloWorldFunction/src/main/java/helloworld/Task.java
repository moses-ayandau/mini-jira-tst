package helloworld;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Task {
    @JsonProperty("taskId")
    private String taskId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("status")
    private String status;

    @JsonProperty("deadline")
    private String deadline;

    @JsonProperty("userId")
    private String userId;

    // Default constructor for Jackson
    public Task() {}

    public Task(String taskId, String name, String description, String status, String deadline, String userId) {
        this.taskId = taskId;
        this.name = name;
        this.description = description;
        this.status = status;
        this.deadline = deadline;
        this.userId = userId;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDeadline() {
        return deadline;
    }

    public void setDeadline(String deadline) {
        this.deadline = deadline;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}